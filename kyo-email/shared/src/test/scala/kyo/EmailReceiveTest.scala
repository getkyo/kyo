package kyo

import kyo.EmailConnectException.Kind as ConnectKind
import kyo.EmailTransportException.Kind
import kyo.internal.charset.Utf8
import kyo.internal.email.imap.ImapSession
import kyo.internal.email.imap.ImapTestServer
import kyo.internal.email.imap.ImapTestServer.*
import kyo.internal.email.net.LineConnection
import kyo.internal.email.net.LineConnectionFixture
import kyo.internal.email.net.LineConnectionFixture.Certificate
import kyo.internal.email.net.LineConnectionFixture.scripted
import kyo.internal.email.net.Sasl
import kyo.net.NetTlsConfig

class EmailReceiveTest extends kyo.test.Test[Any]:

    // Built away from the assertions, so a failing assertion's source snippet never carries a secret.
    private val secret    = "hunter2 \"quoted\""
    private val password  = EmailLiterals.passwordOf(secret)
    private val byPass    = EmailLiterals.passwordAccountOf(User, password)
    private val nonAscii  = "pässwörd"
    private val long      = "é" * 2049
    private val tokenText = "ya29.token-value"
    private val token     = EmailLiterals.tokenOf(tokenText)
    private val byToken   = EmailLiterals.oauth2AccountOf(User, token)
    private val plain     = Sasl.plain(User, password)
    private val xoauth2   = Sasl.xoauth2(User, token)
    private val quoted    = "\"hunter2 \\\"quoted\\\"\""
    private val list      = "LIST \"\" \"*\""

    // Longer than the 200 octets a Protocol failure shows, so an echo of any of their wire forms straddles the cut.
    private val longSecret = "hunter2-\"q\"-" * 20
    private val longPass   = EmailLiterals.passwordAccountOf(User, EmailLiterals.passwordOf(longSecret))
    private val forms      =
        Seq(longSecret, Sasl.plain(User, EmailLiterals.passwordOf(longSecret)), "\"" + longSecret.replace("\"", "\\\"") + "\"")
    private val nonAsciiSecret = "pässwörd-" * 30
    private val shownSecret    = LineConnection.shown(Utf8.encode(nonAsciiSecret))

    private def failure[E, A](v: A < (Async & Abort[E]))(using Frame, ConcreteTag[E]): E < Async =
        Abort.run[E](v).map {
            case Result.Failure(ex) => ex
            case other              => throw new IllegalStateException(s"expected a failure: $other")
        }

    private def transport[A](v: A < (Async & Abort[EmailListMailboxesFailure]))(using Frame): EmailTransportException < Async =
        failure(v).map {
            case ex: EmailTransportException => ex
            case other => throw new IllegalStateException(s"expected a transport failure: $other, from ${Maybe(other.getCause)}", other)
        }

    private def connecting[A](v: A < (Async & Abort[EmailListMailboxesFailure]))(using Frame): EmailConnectException < Async =
        failure(v).map {
            case ex: EmailConnectException => ex
            case other                     => throw new IllegalStateException(s"expected a connect failure: $other")
        }

    private def leaks(ex: Throwable, secrets: String*): Boolean =
        secrets.exists(s => ex.getMessage.contains(s) || ex.toString.contains(s))

    // Whether any run of 20 characters of a secret survives in the failure: a cut that splits a form leaves such a run behind.
    private def leaksFragment(ex: EmailTransportException, secrets: String*): Boolean =
        val rendered = Seq(ex.kind.toString, ex.getMessage, ex.toString)
        secrets.exists(s => s.sliding(20).exists(window => rendered.exists(_.contains(window))))

    // A run whose one verb, listMailboxes, opens the session; `untilClosed` answers it with no mailbox.
    private def opened(config: EmailImapConfig)(using Frame): Unit < (Async & Abort[EmailListMailboxesFailure]) =
        EmailReceive.run(config)(EmailReceive.listMailboxes).unit

    // Opens a session through a verb, ends the run, and answers with every line the server received.
    private def session(server: Server, account: Email.Account = byPass, startTls: Boolean = false)(using
        Frame
    ): Chunk[String] < (Async & Abort[EmailListMailboxesFailure]) =
        ImapTestServer.config(server, account, startTls).map(opened).andThen(server.awaitClose).andThen(server.received)

    "authenticating" - {
        "PLAIN with SASL-IR, the initial response on the command line, over implicit TLS trusting the CA file" in scripted {
            Scope.run {
                for
                    server   <- serve()(peer => peer.ready().andThen(peer.untilClosed))
                    received <- session(server)
                yield assert(received == Chunk(s"A1 AUTHENTICATE PLAIN $plain", s"A2 $list", "A3 LOGOUT"))
            }
        }
        "PLAIN without SASL-IR, the initial response after the continuation" in scripted {
            Scope.run {
                for
                    server <- serve() { peer =>
                        peer.send("* OK [CAPABILITY IMAP4rev1 AUTH=PLAIN] ready")
                            .andThen(peer.command)
                            .map((tag, _) => peer.send("+ ").andThen(peer.receive).andThen(peer.send(s"$tag OK done")))
                            .andThen(peer.untilClosed)
                    }
                    received <- session(server)
                yield assert(received == Chunk("A1 AUTHENTICATE PLAIN", plain, "A2 CAPABILITY", s"A3 $list", "A4 LOGOUT"))
            }
        }
        "LOGIN when PLAIN is not offered, the password as a quoted string" in scripted {
            Scope.run {
                for
                    server   <- serve()(peer => peer.ready("IMAP4rev1").andThen(peer.untilClosed))
                    received <- session(server)
                yield assert(received == Chunk(s"A1 LOGIN $User $quoted", s"A2 $list", "A3 LOGOUT"))
            }
        }
        "LOGIN with a non-ASCII password sends a synchronizing literal after the server's +" in scripted {
            Scope.run {
                for
                    server <- serve() { peer =>
                        peer.send("* OK [CAPABILITY IMAP4rev1] ready")
                            .andThen(peer.command)
                            .map((tag, _) => peer.send("+ go").andThen(peer.receive).andThen(peer.send(s"$tag OK done")))
                            .andThen(peer.untilClosed)
                    }
                    received <- session(server, EmailLiterals.passwordAccountOf(User, EmailLiterals.passwordOf(nonAscii)))
                yield assert(received == Chunk(s"A1 LOGIN $User {10}", nonAscii, "A2 CAPABILITY", s"A3 $list", "A4 LOGOUT"))
            }
        }
        "LOGIN under LITERAL+ sends the literal without waiting" in scripted {
            Scope.run {
                for
                    server <- serve() { peer =>
                        peer.send("* OK [CAPABILITY IMAP4rev1 LITERAL+] ready")
                            .andThen(peer.command)
                            .map((tag, _) => peer.receive.andThen(peer.send(s"$tag OK [CAPABILITY IMAP4rev1] done")))
                            .andThen(peer.untilClosed)
                    }
                    received <- session(server, EmailLiterals.passwordAccountOf(User, EmailLiterals.passwordOf(nonAscii)))
                yield assert(received == Chunk(s"A1 LOGIN $User {10+}", nonAscii, s"A2 $list", "A3 LOGOUT"))
            }
        }
        "LOGIN under LITERAL- sends a literal of up to 4096 octets without waiting (RFC 7888 section 5)" in scripted {
            Scope.run {
                for
                    server <- serve() { peer =>
                        peer.send("* OK [CAPABILITY IMAP4rev1 LITERAL-] ready")
                            .andThen(peer.command)
                            .map((tag, _) => peer.receive.andThen(peer.send(s"$tag OK [CAPABILITY IMAP4rev1] done")))
                            .andThen(peer.untilClosed)
                    }
                    received <- session(server, EmailLiterals.passwordAccountOf(User, EmailLiterals.passwordOf(nonAscii)))
                yield assert(received == Chunk(s"A1 LOGIN $User {10+}", nonAscii, s"A2 $list", "A3 LOGOUT"))
            }
        }
        "LOGIN under LITERAL- waits for the server's + before a literal longer than 4096 octets" in scripted {
            Scope.run {
                for
                    server <- serve() { peer =>
                        peer.send("* OK [CAPABILITY IMAP4rev1 LITERAL-] ready")
                            .andThen(peer.command)
                            .map((tag, _) =>
                                peer.send("+ go").andThen(peer.receive).andThen(peer.send(s"$tag OK [CAPABILITY IMAP4rev1] done"))
                            )
                            .andThen(peer.untilClosed)
                    }
                    received <- session(server, EmailLiterals.passwordAccountOf(User, EmailLiterals.passwordOf(long)))
                yield assert(received == Chunk(s"A1 LOGIN $User {4098}", long, s"A2 $list", "A3 LOGOUT"))
            }
        }
        "XOAUTH2 with SASL-IR" in scripted {
            Scope.run {
                for
                    server   <- serve()(peer => peer.ready("IMAP4rev1 AUTH=XOAUTH2 SASL-IR").andThen(peer.untilClosed))
                    received <- session(server, byToken)
                yield assert(received == Chunk(s"A1 AUTHENTICATE XOAUTH2 $xoauth2", s"A2 $list", "A3 LOGOUT"))
            }
        }
        "a PREAUTH greeting over implicit TLS needs no authentication" in scripted {
            Scope.run {
                for
                    server   <- serve()(peer => peer.send("* PREAUTH [CAPABILITY IMAP4rev1] welcome").andThen(peer.untilClosed))
                    received <- session(server)
                yield assert(received == Chunk(s"A1 $list", "A2 LOGOUT"))
            }
        }
        "capabilities come from a CAPABILITY command when the greeting has none" in scripted {
            Scope.run {
                for
                    server <- serve() { peer =>
                        peer.send("* OK ready")
                            .andThen(peer.command)
                            .map((tag, _) => peer.send("* CAPABILITY IMAP4rev1 AUTH=PLAIN SASL-IR").andThen(peer.send(s"$tag OK done")))
                            .andThen(peer.ok("CAPABILITY IMAP4rev1"))
                            .andThen(peer.untilClosed)
                    }
                    received <- session(server)
                yield assert(received == Chunk("A1 CAPABILITY", s"A2 AUTHENTICATE PLAIN $plain", s"A3 $list", "A4 LOGOUT"))
            }
        }
        "the OAuth token computation's failure is EmailTokenException on the verb's row, before anything connects" in scripted {
            Scope.run {
                for
                    server <- serve()(peer => peer.ready().andThen(peer.untilClosed))
                    revoked = EmailTokenException("the grant was revoked")
                    config <- ImapTestServer.config(server, EmailLiterals.oauth2AccountOf(User, Abort.fail(revoked)))
                    status <- Abort.run[EmailStatusFailure](EmailReceive.run(config)(EmailReceive.status(Email.MailboxName.Inbox)))
                    moved  <- Abort.run[EmailMoveFailure](EmailReceive.run(config)(EmailReceive.move(Seq.empty, Email.MailboxName.Inbox)))
                    connections <- server.connections
                yield
                    assert(status == Result.fail(revoked))
                    assert(moved == Result.fail(revoked))
                    assert(connections == 0)
            }
        }
    }

    "STARTTLS" - {
        "upgrades, discards what the server sent before the handshake, and asks for the capabilities again" in scripted {
            Scope.run {
                for
                    server <- serve(tls = false) { peer =>
                        peer.send("* OK [CAPABILITY IMAP4rev1 STARTTLS LOGINDISABLED] ready")
                            .andThen(peer.command)
                            .map((tag, _) => peer.send(s"$tag OK begin TLS\r\n* CAPABILITY IMAP4rev1 AUTH=XOAUTH2 INJECTED"))
                            .andThen(peer.startTls())
                            .andThen(peer.command)
                            .map((tag, _) => peer.send("* CAPABILITY IMAP4rev1 AUTH=PLAIN SASL-IR").andThen(peer.send(s"$tag OK done")))
                            .andThen(peer.ok("CAPABILITY IMAP4rev1"))
                            .andThen(peer.untilClosed)
                    }
                    received <- session(server, startTls = true)
                yield assert(received == Chunk("A1 STARTTLS", "A2 CAPABILITY", s"A3 AUTHENTICATE PLAIN $plain", s"A4 $list", "A5 LOGOUT"))
            }
        }
        "a server that does not offer it is StartTlsUnavailable, and the connection closes before any credential" in scripted {
            Scope.run {
                for
                    server <- serve(tls = false)(peer => peer.send(s"* OK [CAPABILITY $Capabilities] ready").andThen(peer.untilClosed))
                    config <- ImapTestServer.config(server, byPass, startTls = true)
                    ex     <- connecting(opened(config))
                    _      <- server.awaitClose
                    seen   <- server.received
                yield
                    assert(ex.kind == ConnectKind.StartTlsUnavailable && ex.method == "listMailboxes")
                    assert(seen.isEmpty)
            }
        }
        "a PREAUTH greeting on a plaintext connection is StartTlsUnavailable" in scripted {
            Scope.run {
                for
                    server <-
                        serve(tls = false)(peer => peer.send("* PREAUTH [CAPABILITY IMAP4rev1 STARTTLS] hi").andThen(peer.untilClosed))
                    config <- ImapTestServer.config(server, byPass, startTls = true)
                    ex     <- connecting(opened(config))
                yield assert(ex.kind == ConnectKind.StartTlsUnavailable)
            }
        }
        "a certificate for another host after the upgrade is Tls" in scripted {
            Scope.run {
                for
                    server <- serve(tls = false) { peer =>
                        peer.send(s"* OK [CAPABILITY IMAP4rev1 STARTTLS] ready")
                            .andThen(peer.ok())
                            .andThen(peer.startTls(Certificate.WrongHost))
                    }
                    config <- ImapTestServer.config(server, byPass, startTls = true)
                    ex     <- connecting(opened(config))
                yield assert(ex.kind == ConnectKind.Tls)
            }
        }
    }

    "TLS options" - {
        def openedOn(config: EmailImapConfig, server: Server)(using Frame) =
            opened(config).andThen(server.awaitClose).andThen(server.received)
        "a client certificate the server requires is presented, and the session opens" in scripted {
            Scope.run {
                for
                    server <-
                        serve(adjust = LineConnectionFixture.requiringClientCertificate)(peer => peer.ready().andThen(peer.untilClosed))
                    certificate <- LineConnectionFixture.clientCertificate
                    config      <- ImapTestServer.config(server, byPass)
                    received    <-
                        openedOn(
                            EmailLiterals.valid(config.tls(EmailLiterals.implicitTlsOf(
                                config.tls.trust,
                                clientCertificate = Present(certificate)
                            ))),
                            server
                        )
                yield assert(received == Chunk(s"A1 AUTHENTICATE PLAIN $plain", s"A2 $list", "A3 LOGOUT"))
            }
        }
        "without a client certificate over TLS 1.2 the server refuses the handshake: Tls, and no credential is sent" in scripted {
            Scope.run {
                for
                    server <-
                        serve(adjust = LineConnectionFixture.requiringClientCertificate)(peer => peer.ready().andThen(peer.untilClosed))
                    config <- ImapTestServer.config(server, byPass)
                    tls = EmailLiterals.implicitTlsOf(config.tls.trust, maxVersion = Email.Tls.Version.TLS12)
                    ex   <- connecting(opened(EmailLiterals.valid(config.tls(tls))))
                    seen <- server.received
                yield
                    assert(ex.kind == ConnectKind.Tls && ex.method == "listMailboxes")
                    assert(seen.isEmpty)
            }
        }
        "without a client certificate over TLS 1.3 the session does not open, and no credential is sent" in scripted {
            Scope.run {
                for
                    server <-
                        serve(adjust = LineConnectionFixture.requiringClientCertificate)(peer => peer.ready().andThen(peer.untilClosed))
                    config <- ImapTestServer.config(server, byPass)
                    tls = EmailLiterals.implicitTlsOf(config.tls.trust, minVersion = Email.Tls.Version.TLS13)
                    result <- Abort.run[EmailListMailboxesFailure](opened(EmailLiterals.valid(config.tls(tls))))
                    seen   <- server.received
                yield
                    result match
                        case Result.Failure(ex: EmailConnectException)   => assert(ex.kind == ConnectKind.Tls, s"failed with $ex")
                        case Result.Failure(ex: EmailTransportException) =>
                            assert(ex.kind == Kind.ConnectionClosed(Absent), s"failed with $ex")
                        case other => fail(other.toString)
                    end match
                    assert(seen.isEmpty)
            }
        }
        "a client certificate whose files do not exist is TlsSetup, before any credential" in scripted {
            Scope.run {
                for
                    server <- serve()(peer => peer.ready().andThen(peer.untilClosed))
                    config <- ImapTestServer.config(server, byPass)
                    missing = Email.Tls.ClientCertificate(
                        Path("/nonexistent/kyo-email-client.pem"),
                        Path("/nonexistent/kyo-email-client-key.pem")
                    )
                    ex <- connecting(opened(EmailLiterals.valid(config.tls(
                        EmailLiterals.implicitTlsOf(config.tls.trust, clientCertificate = Present(missing))
                    ))))
                    seen <- server.received
                yield
                    assert(ex.kind == ConnectKind.TlsSetup && ex.method == "listMailboxes", s"failed with $ex")
                    assert(seen.isEmpty)
            }
        }
        "a minimum version the server does not offer is Tls, before any credential" in scripted {
            Scope.run {
                for
                    server <-
                        serve(adjust = _.copy(maxVersion = NetTlsConfig.Version.TLS12))(peer => peer.ready().andThen(peer.untilClosed))
                    config <- ImapTestServer.config(server, byPass)
                    ex     <- connecting(opened(EmailLiterals.valid(config.tls(
                        EmailLiterals.implicitTlsOf(config.tls.trust, minVersion = Email.Tls.Version.TLS13)
                    ))))
                    seen <- server.received
                yield
                    assert(ex.kind == ConnectKind.Tls && ex.method == "listMailboxes")
                    assert(seen.isEmpty)
            }
        }
        "the default version range connects to a server that offers only TLS 1.2" in scripted {
            Scope.run {
                for
                    server <-
                        serve(adjust = _.copy(maxVersion = NetTlsConfig.Version.TLS12))(peer => peer.ready().andThen(peer.untilClosed))
                    config   <- ImapTestServer.config(server, byPass)
                    received <- openedOn(config, server)
                yield assert(received == Chunk(s"A1 AUTHENTICATE PLAIN $plain", s"A2 $list", "A3 LOGOUT"))
            }
        }
    }

    "connect failures" - {
        "a refused connection is Connect" in scripted {
            Scope.run(serve()(_ => ())).map { server =>
                ImapTestServer.config(server, byPass).map(config => connecting(opened(config))).map { ex =>
                    assert(ex.kind == ConnectKind.Connect && ex.method == "listMailboxes" && ex.port == server.port)
                }
            }
        }
        "a host that does not resolve is Dns" in scripted {
            Scope.run {
                for
                    server <- serve()(_ => ())
                    config <- ImapTestServer.config(server, byPass)
                    ex     <- connecting(opened(EmailLiterals.valid(config.host("nonexistent.invalid"))))
                yield assert(ex.kind == ConnectKind.Dns && ex.host == "nonexistent.invalid")
            }
        }
        "a CA file that does not exist is TlsSetup, and nothing reaches the server" in scripted {
            Scope.run {
                for
                    server <- serve()(peer => peer.untilClosed)
                    config <- ImapTestServer.config(server, byPass, trust = Present(Email.Tls.Trust.CaFile(Path("/nonexistent/ca.pem"))))
                    ex     <- connecting(opened(config))
                    seen   <- server.received
                yield
                    assert(ex.kind == ConnectKind.TlsSetup && ex.method == "listMailboxes")
                    assert(seen.isEmpty)
            }
        }
        "a certificate for another host over implicit TLS is Tls" in scripted {
            Scope.run {
                for
                    server <- serve(certificate = Certificate.WrongHost)(peer => peer.ready())
                    config <- ImapTestServer.config(server, byPass)
                    ex     <- connecting(opened(config))
                yield assert(ex.kind == ConnectKind.Tls)
            }
        }
        "the trust is the config's own: the system trust store refuses the fixture's certificate" in scripted {
            Scope.run {
                for
                    server <- serve()(peer => peer.ready())
                    config <- ImapTestServer.config(server, byPass, trust = Present(Email.Tls.Trust.System))
                    ex     <- connecting(opened(config))
                yield assert(ex.kind == ConnectKind.Tls)
            }
        }
        "a BYE greeting is ConnectionClosed with the server's text" in scripted {
            Scope.run {
                for
                    server <- serve()(peer => peer.send("* BYE too many connections"))
                    config <- ImapTestServer.config(server, byPass)
                    ex     <- transport(opened(config))
                yield assert(ex.kind == Kind.ConnectionClosed(Present("too many connections")))
            }
        }
        "a server closing during authentication is ConnectionClosed with no text" in scripted {
            Scope.run {
                for
                    server <-
                        serve()(peer => peer.send(s"* OK [CAPABILITY $Capabilities] ready").andThen(peer.command).andThen(peer.line.close))
                    config <- ImapTestServer.config(server, byPass)
                    ex     <- transport(opened(config))
                yield assert(ex.kind == Kind.ConnectionClosed(Absent))
            }
        }
        "a greeting that is not IMAP is Protocol" in scripted {
            Scope.run {
                for
                    server <- serve()(peer => peer.send("220 smtp.example.com ESMTP"))
                    config <- ImapTestServer.config(server, byPass)
                    ex     <- transport(opened(config))
                yield assert(ex.kind == Kind.Protocol("220 smtp.example.com ESMTP"))
            }
        }
        "a TLS handshake that never completes is ConnectTimeout after connectTimeout, on kyo's clock" in scripted {
            Scope.run {
                Latch.init(1).map { accepted =>
                    serve(tls = false)(peer => accepted.release.andThen(peer.receive)).map { server =>
                        ImapTestServer.config(server, byPass).map { config =>
                            Clock.withTimeControl { control =>
                                Fiber.initUnscoped(connecting(opened(config))).map { fiber =>
                                    accepted.await.andThen(control.awaitPendingSleepers(1))
                                        .andThen(control.advance(config.connectTimeout)).andThen(fiber.get).map { ex =>
                                            assert(ex.kind == ConnectKind.ConnectTimeout && ex.timeout == Present(config.connectTimeout))
                                        }
                                }
                            }
                        }
                    }
                }
            }
        }
        "a command the server never answers is Timeout after commandTimeout, on kyo's clock" in scripted {
            Scope.run {
                Latch.init(1).map { commanded =>
                    serve() { peer =>
                        peer.send(s"* OK [CAPABILITY $Capabilities] ready").andThen(peer.command).andThen(commanded.release)
                            .andThen(peer.receive)
                    }.map { server =>
                        ImapTestServer.config(server, byPass).map { config =>
                            Clock.withTimeControl { control =>
                                Fiber.initUnscoped(transport(opened(config))).map { fiber =>
                                    commanded.await.andThen(control.awaitPendingSleepers(1))
                                        .andThen(control.advance(config.commandTimeout)).andThen(fiber.get).map { ex =>
                                            assert(ex.kind == Kind.Timeout && ex.timeout == Present(config.commandTimeout))
                                        }
                                }
                            }
                        }
                    }
                }
            }
        }
        "CAPABILITY answered BAD is EmailImapCommandException" in scripted {
            Scope.run {
                for
                    server <- serve() { peer =>
                        peer.send("* OK ready").andThen(peer.command).map((tag, _) => peer.send(s"$tag BAD [CLIENTBUG] unknown command"))
                    }
                    config <- ImapTestServer.config(server, byPass)
                    ex     <- failure(opened(config))
                yield assert(ex == EmailImapCommandException(
                    "listMailboxes",
                    "CAPABILITY",
                    EmailImapCommandException.Status.Bad,
                    Present(EmailReceive.ResponseCode.ClientBug),
                    "unknown command"
                ))
            }
        }
    }

    "authentication failures" - {
        def refusedBy(capabilities: String, auth: Email.Account, mechanism: Email.Auth.Mechanism, continuation: Boolean = false)(using
            Frame,
            kyo.test.AssertScope
        ) =
            Scope.run {
                for
                    server <- serve() { peer =>
                        peer.send(s"* OK [CAPABILITY $capabilities] ready").andThen(peer.command).map { (tag, _) =>
                            (if continuation then peer.send("+ eyJzdGF0dXMiOiI0MDEifQ==").andThen(peer.receive).unit else Kyo.unit)
                                .andThen(peer.send(s"$tag NO [AUTHENTICATIONFAILED] Invalid credentials"))
                        }
                    }
                    config <- ImapTestServer.config(server, auth)
                    ex     <- failure(opened(config))
                    seen   <- server.received
                yield
                    assert(
                        ex == EmailAuthenticationException(
                            "listMailboxes",
                            "127.0.0.1",
                            User,
                            mechanism,
                            EmailAuthenticationException.Reply.Imap(
                                Present(EmailReceive.ResponseCode.AuthenticationFailed),
                                "Invalid credentials"
                            )
                        ),
                        s"failed with $ex"
                    )
                    assert(!leaks(ex, secret, tokenText, plain, xoauth2, quoted))
                    seen
            }
        "PLAIN answered NO" in scripted {
            refusedBy(Capabilities, byPass, Email.Auth.Mechanism.Plain).map(_ => succeed)
        }
        "LOGIN answered NO" in scripted {
            refusedBy("IMAP4rev1", byPass, Email.Auth.Mechanism.Login).map(_ => succeed)
        }
        "XOAUTH2 answered with its JSON error continuation, then NO after the client's empty answer" in scripted {
            refusedBy("IMAP4rev1 AUTH=XOAUTH2 SASL-IR", byToken, Email.Auth.Mechanism.XOAuth2, continuation = true).map { seen =>
                assert(seen == Chunk(s"A1 AUTHENTICATE XOAUTH2 $xoauth2", ""))
            }
        }
        "a password against LOGINDISABLED without AUTH=PLAIN is AuthMechanismUnavailable, naming what is offered" in scripted {
            Scope.run {
                for
                    server <- serve()(peer => peer.send("* OK [CAPABILITY IMAP4rev1 LOGINDISABLED AUTH=XOAUTH2 AUTH=GSSAPI] ready"))
                    config <- ImapTestServer.config(server, byPass)
                    ex     <- failure(opened(config))
                yield assert(ex == EmailAuthMechanismUnavailableException(
                    "listMailboxes",
                    EmailException.Protocol.Imap,
                    "127.0.0.1",
                    Chunk(Email.Auth.Mechanism.Plain, Email.Auth.Mechanism.Login),
                    Chunk("GSSAPI", "XOAUTH2")
                ))
            }
        }
        "a token against a server without AUTH=XOAUTH2 is AuthMechanismUnavailable" in scripted {
            Scope.run {
                for
                    server <- serve()(peer => peer.send(s"* OK [CAPABILITY $Capabilities] ready"))
                    config <- ImapTestServer.config(server, byToken)
                    ex     <- failure(opened(config))
                yield assert(ex == EmailAuthMechanismUnavailableException(
                    "listMailboxes",
                    EmailException.Protocol.Imap,
                    "127.0.0.1",
                    Chunk(Email.Auth.Mechanism.XOAuth2),
                    Chunk("PLAIN")
                ))
            }
        }
    }

    "server text never carries the credential" - {
        "a response code's arguments echoing the password, in a refused LOGIN and a refused command" in scripted {
            Scope.run {
                for
                    login <- serve()(peer =>
                        peer.send("* OK [CAPABILITY IMAP4rev1] ready").andThen(peer.command).map((tag, _) =>
                            peer.send(s"$tag NO [XFOO $secret $quoted] no")
                        )
                    )
                    loginConfig  <- ImapTestServer.config(login, byPass)
                    refusedLogin <- failure(opened(loginConfig))
                    capability   <- serve()(peer =>
                        peer.send("* OK ready").andThen(peer.command).map((tag, _) => peer.send(s"$tag BAD [XFOO $secret] no"))
                    )
                    capabilityConfig <- ImapTestServer.config(capability, byPass)
                    refusedCommand   <- failure(opened(capabilityConfig))
                yield
                    refusedLogin match
                        case ex: EmailAuthenticationException =>
                            assert(ex.reply == EmailAuthenticationException.Reply.Imap(
                                Present(EmailReceive.ResponseCode.Other("XFOO", Present("<redacted> <redacted>"))),
                                "no"
                            ))
                        case other => fail(s"expected EmailAuthenticationException: $other")
                    end match
                    refusedCommand match
                        case ex: EmailImapCommandException =>
                            assert(ex.responseCode == Present(EmailReceive.ResponseCode.Other("XFOO", Present("<redacted>"))))
                        case other => fail(s"expected EmailImapCommandException: $other")
                    end match
                    assert(!leaks(refusedLogin, secret, quoted, "hunter2") && !leaks(refusedCommand, secret, quoted, "hunter2"))
            }
        }
        "a greeting holding the password" in scripted {
            Scope.run {
                for
                    server <- serve()(peer => peer.send(s"* BYE $secret"))
                    config <- ImapTestServer.config(server, byPass)
                    ex     <- transport(opened(config))
                yield
                    assert(ex.kind == Kind.ConnectionClosed(Present("<redacted>")))
                    assert(!leaks(ex, secret))
            }
        }
        "a LOGIN line echoed into BYE" in scripted {
            Scope.run {
                for
                    server <- serve()(peer =>
                        peer.send("* OK [CAPABILITY IMAP4rev1] ready").andThen(peer.receive).map(line => peer.send(s"* BYE $line"))
                    )
                    config <- ImapTestServer.config(server, byPass)
                    ex     <- transport(opened(config))
                yield
                    assert(ex.kind == Kind.ConnectionClosed(Present(s"A1 LOGIN $User <redacted>")))
                    assert(!leaks(ex, secret, quoted, "hunter2"))
            }
        }
        "PLAIN's initial response echoed as an invalid response" in scripted {
            Scope.run {
                for
                    server <- serve()(peer =>
                        peer.send(s"* OK [CAPABILITY $Capabilities] ready").andThen(peer.receive).map(line => peer.send(s"?? $line"))
                    )
                    config <- ImapTestServer.config(server, byPass)
                    ex     <- transport(opened(config))
                yield
                    assert(ex.kind == Kind.Protocol("?? A1 AUTHENTICATE PLAIN <redacted>"))
                    assert(!leaks(ex, secret, plain))
            }
        }
        "a password longer than the shown text leaves no fragment when its echo straddles the cut" - {
            "in an invalid response" in scripted {
                Scope.run {
                    for
                        server <- serve()(peer =>
                            peer.send(s"* OK [CAPABILITY $Capabilities] ready").andThen(peer.receive).map(line => peer.send(s"?? $line"))
                        )
                        config <- ImapTestServer.config(server, longPass)
                        ex     <- transport(opened(config))
                    yield
                        assert(!leaksFragment(ex, forms*))
                        assert(ex.kind == Kind.Protocol("?? A1 AUTHENTICATE PLAIN <redacted>"))
                }
            }
            "in a BYE" in scripted {
                Scope.run {
                    for
                        server <- serve()(peer =>
                            peer.send("* OK [CAPABILITY IMAP4rev1] ready").andThen(peer.receive).map(line => peer.send(s"* BYE $line"))
                        )
                        config <- ImapTestServer.config(server, longPass)
                        ex     <- transport(opened(config))
                    yield
                        assert(ex.kind == Kind.ConnectionClosed(Present(s"A1 LOGIN $User <redacted>")))
                        assert(!leaksFragment(ex, forms*))
                }
            }
            "in a line over the line limit, the password not ASCII" in scripted {
                Scope.run {
                    for
                        server <- serve()(peer => peer.send(s"* OK $nonAsciiSecret" + "x" * ImapSession.LineLimit))
                        config <-
                            ImapTestServer.config(server, EmailLiterals.passwordAccountOf(User, EmailLiterals.passwordOf(nonAsciiSecret)))
                        ex <- transport(opened(config))
                    yield
                        assert(!leaksFragment(ex, nonAsciiSecret, shownSecret))
                        assert(ex.kind == Kind.Protocol("* OK <redacted>" + "x" * (LineConnection.ShownOctets - 15)))
                }
            }
        }
        "XOAUTH2's initial response echoed into the NO text" in scripted {
            Scope.run {
                for
                    server <- serve() { peer =>
                        peer.send("* OK [CAPABILITY IMAP4rev1 AUTH=XOAUTH2 SASL-IR] ready").andThen(peer.receive).map { line =>
                            peer.send(s"A1 NO rejected $line")
                        }
                    }
                    config <- ImapTestServer.config(server, byToken)
                    ex     <- failure(opened(config))
                yield ex match
                    case ex: EmailAuthenticationException =>
                        assert(ex.reply == EmailAuthenticationException.Reply.Imap(Absent, "rejected A1 AUTHENTICATE XOAUTH2 <redacted>"))
                        assert(!leaks(ex, tokenText, xoauth2))
                    case other => fail(other.toString)
            }
        }
    }

    "the session of a run" - {
        "a run whose computation reaches no verb never connects" in scripted {
            Scope.run {
                for
                    server      <- serve()(peer => peer.ready().andThen(peer.untilClosed))
                    config      <- ImapTestServer.config(server, byPass)
                    result      <- EmailReceive.run(config)(42)
                    connections <- server.connections
                yield assert(result == 42 && connections == 0)
            }
        }
        "the first verb connects, concurrent and later verbs share its one login, and the run's end logs out" in scripted {
            Scope.run {
                for
                    server <- serve()(peer => peer.ready().andThen(peer.untilClosed))
                    config <- ImapTestServer.config(server, byPass)
                    _      <- EmailReceive.run(config) {
                        Async.zip(EmailReceive.listMailboxes, EmailReceive.listMailboxes).andThen(EmailReceive.listMailboxes)
                    }
                    _           <- server.awaitClose
                    connections <- server.connections
                    seen        <- server.received
                yield
                    assert(connections == 1)
                    assert(seen == Chunk(s"A1 AUTHENTICATE PLAIN $plain", s"A2 $list", s"A3 $list", s"A4 $list", "A5 LOGOUT"))
            }
        }
        "the session is logged out when the computation fails" in scripted {
            Scope.run {
                for
                    server <- serve()(peer => peer.ready().andThen(peer.untilClosed))
                    config <- ImapTestServer.config(server, byPass)
                    result <- Abort.run[String](EmailReceive.run(config)(EmailReceive.listMailboxes.andThen(Abort.fail("stopped"))))
                    _      <- server.awaitClose
                    seen   <- server.received
                yield
                    assert(result == Result.fail("stopped"))
                    assert(seen == Chunk(s"A1 AUTHENTICATE PLAIN $plain", s"A2 $list", "A3 LOGOUT"))
            }
        }
        "the session is logged out when the computation is interrupted" in scripted {
            Scope.run {
                for
                    server <- serve()(peer => peer.ready().andThen(peer.untilClosed))
                    config <- ImapTestServer.config(server, byPass)
                    listed <- Latch.init(1)
                    held   <- Latch.init(1)
                    fiber  <-
                        Fiber.initUnscoped(EmailReceive.run(config)(EmailReceive.listMailboxes.andThen(listed.release).andThen(held.await)))
                    _    <- listed.await
                    _    <- fiber.interrupt
                    _    <- server.awaitClose
                    seen <- server.received
                yield assert(seen == Chunk(s"A1 AUTHENTICATE PLAIN $plain", s"A2 $list", "A3 LOGOUT"))
            }
        }
        "a verb after a failure closed the session connects again, and the failed verb fails alone" in scripted {
            Scope.run {
                for
                    server <- serve() { peer =>
                        if peer.index == 1 then peer.ready().andThen(peer.answer("* BYE shutting down"))
                        else peer.ready().andThen(peer.untilClosed)
                    }
                    config          <- ImapTestServer.config(server, byPass)
                    (first, second) <- EmailReceive.run(config) {
                        Abort.run[EmailListMailboxesFailure](EmailReceive.listMailboxes).map(first =>
                            EmailReceive.listMailboxes.map((first, _))
                        )
                    }
                    connections <- server.connections
                yield
                    first match
                        case Result.Failure(ex: EmailTransportException) =>
                            assert(ex.kind == Kind.ConnectionClosed(Present("shutting down")) && ex.method == "listMailboxes")
                        case other => fail(s"expected the closed connection: $other")
                    end match
                    assert(second == Chunk.empty)
                    assert(connections == 2)
            }
        }
        "a verb inside nested runs uses the innermost run's session" in scripted {
            Scope.run {
                for
                    outer       <- serve()(peer => peer.ready().andThen(peer.untilClosed))
                    inner       <- serve()(peer => peer.ready("IMAP4rev1 AUTH=XOAUTH2 SASL-IR").andThen(peer.untilClosed))
                    outerConfig <- ImapTestServer.config(outer, byPass)
                    innerConfig <- ImapTestServer.config(inner, byToken)
                    _           <- EmailReceive.run(outerConfig)(EmailReceive.run(innerConfig)(EmailReceive.listMailboxes))
                    _           <- inner.awaitClose
                    outerSeen   <- outer.connections
                    innerSeen   <- inner.received
                yield
                    assert(outerSeen == 0)
                    assert(innerSeen == Chunk(s"A1 AUTHENTICATE XOAUTH2 $xoauth2", s"A2 $list", "A3 LOGOUT"))
            }
        }
        "a verb outside EmailReceive.run does not compile" in {
            typeCheck("""
                def config: kyo.EmailImapConfig = ???
                val handled: kyo.Chunk[kyo.EmailReceive.Mailbox] < (kyo.Async & kyo.Abort[kyo.EmailListMailboxesFailure]) =
                    kyo.EmailReceive.run(config)(kyo.EmailReceive.listMailboxes)
            """)
            typeCheckFailure("""
                val unhandled: kyo.Chunk[kyo.EmailReceive.Mailbox] < (kyo.Async & kyo.Abort[kyo.EmailListMailboxesFailure]) =
                    kyo.EmailReceive.listMailboxes
            """)("EmailReceive")
        }
        "the run's logout reads past LOGOUT's BYE to its tagged OK (RFC 9051 section 3.4), waiting commandTimeout for it" in scripted {
            Scope.run {
                for
                    bye    <- Latch.init(1)
                    server <- serve() { peer =>
                        peer.ready().andThen(peer.ok()).andThen(peer.command).andThen(peer.send("* BYE logging out")).andThen(bye.release)
                    }
                    config <- ImapTestServer.config(server, byPass)
                    // LOGOUT's deadline is the one sleep armed once BYE is sent. Short of it by a nanosecond nothing wakes, so the logout is
                    // still reading.
                    (early, closed) <- Clock.withTimeControl { control =>
                        Fiber.initUnscoped(opened(config)).map { fiber =>
                            for
                                _      <- bye.await.andThen(control.awaitPendingSleepers(1))
                                _      <- control.advance(config.commandTimeout.minusOrZero(1.nanos))
                                early  <- fiber.done
                                _      <- control.advance(1.nanos)
                                closed <- fiber.get.andThen(fiber.done)
                            yield (early, closed)
                        }
                    }
                yield assert(!early && closed)
            }
        }
        "an open interrupted while it waits for the greeting, or for its authentication's reply, closes its connection" in scripted {
            def interrupted(script: Peer => Any < (Async & Abort[kyo.internal.email.net.LineConnectionFixture.Failure]))(using Frame) =
                Scope.run {
                    for
                        reached <- Latch.init(1)
                        server  <- serve()(peer => Abort.run(script(peer)).andThen(reached.release).andThen(peer.untilClosed))
                        config  <- ImapTestServer.config(server, byPass)
                        fiber   <- Fiber.initUnscoped(opened(config))
                        _       <- reached.await
                        _       <- fiber.interrupt
                        _       <- server.awaitClose
                        seen    <- server.received
                    yield seen
                }
            interrupted(_ => Kyo.unit).map(seen => assert(seen.isEmpty)).andThen {
                interrupted(peer => peer.send(s"* OK [CAPABILITY $Capabilities] ready").andThen(peer.receive))
                    .map(seen => assert(seen == Chunk(s"A1 AUTHENTICATE PLAIN $plain")))
            }
        }
        "a session's close is idempotent" in scripted {
            Scope.run {
                for
                    server  <- serve()(peer => peer.ready().andThen(peer.untilClosed))
                    config  <- ImapTestServer.config(server, byPass)
                    session <- ImapSession.open("listMailboxes", config)
                    _       <- session.close
                    _       <- session.close
                    _       <- server.awaitClose
                    seen    <- server.received
                yield assert(seen == Chunk(s"A1 AUTHENTICATE PLAIN $plain", "A2 LOGOUT"))
            }
        }
    }

end EmailReceiveTest
