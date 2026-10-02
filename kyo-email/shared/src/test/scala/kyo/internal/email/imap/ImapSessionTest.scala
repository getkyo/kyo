package kyo.internal.email.imap

import kyo.*
import kyo.EmailTransportException.Kind
import kyo.internal.email.imap.ImapTestServer.*
import kyo.internal.email.net.LineConnection
import kyo.internal.email.net.LineConnectionFixture
import kyo.internal.email.net.LineConnectionFixture.scripted
import kyo.net.internal.transport.Connection

class ImapSessionTest extends kyo.test.Test[Any]:

    private val inbox = EmailLiterals.mailboxOf("INBOX")

    private def config(using Frame): EmailImapConfig[Nothing] = configWith(1.minute)

    private def configWith(commandTimeout: Duration)(using Frame): EmailImapConfig[Nothing] =
        EmailLiterals.valid(EmailImapConfig.init(
            "127.0.0.1",
            EmailLiterals.passwordAuthOf(User, EmailLiterals.passwordOf("s3cret-pass")),
            port = Present(993),
            commandTimeout = commandTimeout
        ))

    private def raw(peer: Peer, text: String)(using Frame) = peer.line.write("fixture", LineConnectionFixture.octets(text))

    // A session and a peer on the server's end of its connection, the session authenticated and able to idle. The server writes through
    // a channel of no capacity, so a write returns only once the session's reader has taken it.
    private def paired[A](settings: EmailImapConfig[Nothing])(script: Peer => A < (Async & Abort[LineConnectionFixture.Failure]))(using
        Frame
    ): (ImapSession, Fiber[Result[LineConnectionFixture.Failure, A], Any], AtomicRef[Chunk[String]]) <
        (Async & Abort[EmailConnectFailure]) =
        for
            ends <- Sync.Unsafe.defer {
                // Unsafe: the in-memory connection pair is unsafe-tier; the channels are built here so the server's side has no capacity.
                val toClient = Channel.Unsafe.init[Span[Byte]](0)
                val toServer = Channel.Unsafe.init[Span[Byte]](Int.MaxValue)
                (Connection.inMemory(inbound = toClient, outbound = toServer), Connection.inMemory(inbound = toServer, outbound = toClient))
            }
            client <- LineConnection.wrap(ends._1, "127.0.0.1", 993)
            server <- LineConnection.wrap(ends._2, "127.0.0.1", 993)
            log    <- AtomicRef.init(Chunk.empty[String])
            closed <- Latch.init(1)
            peer = new Peer(server, log, closed, 1)
            fiber <- Fiber.initUnscoped(Abort.run[LineConnectionFixture.Failure](peer.ready(authenticated =
                "IMAP4rev1 IDLE"
            ).andThen(script(peer))))
            session <- ImapSession.over("run", settings, client)
        yield (session, fiber, log)

    "idling" - {
        "a response in progress when idleRenewal passes is read whole, and the IDLE completes" in scripted {
            Latch.init(1).map { inProgress =>
                Latch.init(1).map { doneSeen =>
                    // No command deadline, so the renewal is the one sleep armed while the IDLE runs.
                    paired(configWith(Duration.Infinity)) { peer =>
                        peer.command.map { (tag, _) =>
                            peer.send("+ idling")
                                .andThen(raw(peer, "* 1 FETCH (UID 1 BODY[] {10}\r\nabc"))
                                .andThen(raw(peer, "de"))
                                .andThen(inProgress.release)
                                .andThen(peer.receive)
                                .andThen(doneSeen.release)
                                .andThen(raw(peer, "fghij)\r\n"))
                                .andThen(peer.send(s"$tag OK IDLE terminated"))
                        }
                    }.map { (session, server, log) =>
                        Clock.withTimeControl { control =>
                            session.announcements.map { mark =>
                                Fiber.initUnscoped(Abort.run[EmailRunFailure](session.idle("run", inbox, 1.minute, mark))).map { idle =>
                                    inProgress.await
                                        .andThen(control.awaitPendingSleepers(1))
                                        .andThen(control.advance(1.minute))
                                        .andThen(doneSeen.await)
                                        .andThen(idle.get)
                                }
                            }
                        }.map { result =>
                            server.get.andThen(log.get).map { seen =>
                                session.abandon.andThen {
                                    assert(result == Result.succeed(()), result.toString)
                                    assert(seen.takeRight(2) == Chunk("A2 IDLE", "DONE"))
                                }
                            }
                        }
                    }
                }
            }
        }
        "a completion that does not follow DONE within commandTimeout is Timeout, and the connection is closed" in scripted {
            Latch.init(1).map { doneSeen =>
                // The EXISTS ends the IDLE, so once DONE is seen the completion's deadline is the one sleep armed.
                paired(config) { peer =>
                    peer.command.andThen(peer.send("+ idling")).andThen(peer.send("* 1 EXISTS")).andThen(peer.receive)
                        .andThen(doneSeen.release)
                }.map { (session, _, _) =>
                    Clock.withTimeControl { control =>
                        Fiber.initUnscoped(Abort.run[EmailRunFailure](session.idle("run", inbox, 1.minute, 0))).map { idle =>
                            doneSeen.await
                                .andThen(control.awaitPendingSleepers(1))
                                .andThen(control.advance(config.commandTimeout))
                                .andThen(idle.get)
                        }
                    }.map { result =>
                        Abort.run[EmailRunFailure](session.reselect("run", inbox, EmailLiterals.uidValidityOf(7))).map { after =>
                            result match
                                case Result.Failure(ex: EmailTransportException) =>
                                    assert(ex.kind == Kind.Timeout && ex.timeout == Present(config.commandTimeout))
                                case other => fail(other.toString)
                            end match
                            after match
                                case Result.Failure(ex: EmailTransportException) => assert(ex.kind == Kind.ConnectionClosed(Absent))
                                case other                                       => fail(other.toString)
                        }
                    }
                }
            }
        }
    }

end ImapSessionTest
