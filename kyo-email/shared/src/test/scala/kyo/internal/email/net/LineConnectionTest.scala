package kyo.internal.email.net

import kyo.*
import kyo.EmailConnectException.Kind as ConnectKind
import kyo.EmailTransportException.Kind
import kyo.internal.email.net.LineConnectionFixture.*
import kyo.net.NetTlsConfig

class LineConnectionTest extends kyo.test.Test[Any]:

    private def open(port: Int, tls: Maybe[NetTlsConfig] = Absent, timeout: Duration = 5.seconds)(using Frame) =
        LineConnection.open("init", "127.0.0.1", port, tls, timeout)

    private def failure[A](v: A < (Async & Abort[EmailTransportException]))(using Frame): EmailTransportException < Async =
        Abort.run[EmailTransportException](v).map {
            case Result.Failure(ex) => ex
            case other              => throw new IllegalStateException(s"expected a transport failure: $other")
        }

    private def refused[A](v: A < (Async & Abort[EmailConnectException]))(using Frame): EmailConnectException < Async =
        Abort.run[EmailConnectException](v).map {
            case Result.Failure(ex) => ex
            case other              => throw new IllegalStateException(s"expected a connect failure: $other")
        }

    // The server writes its pieces one at a time and waits for the client's go-ahead line between them, so every read below crosses a
    // chunk boundary deterministically.
    private def serveInPieces(pieces: String*)(using Frame) =
        listen() { server =>
            Kyo.foreachDiscard(pieces)(piece => server.write("fixture", octets(piece)).andThen(server.readLine("fixture", 100)))
        }

    "lines" - {
        "a line split across writes is read whole, and what follows its line end stays for the next read" in scripted {
            Scope.run {
                for
                    port   <- serveInPieces("* OK he", "llo\r\n* NEXT", "\r\n")
                    client <- open(port)
                    _      <- client.write("t", octets("go\r\n"))
                    _      <- client.write("t", octets("go\r\n"))
                    first  <- client.readLine("t", 100)
                    _      <- client.write("t", octets("go\r\n"))
                    second <- client.readLine("t", 100)
                yield assert(text(first) == "* OK hello" && text(second) == "* NEXT")
            }
        }
        "a bare LF ends a line too, and a CR elsewhere stays in it" in scripted {
            Scope.run {
                for
                    port   <- serveInPieces("a\rb\nc\r\n")
                    client <- open(port)
                    _      <- client.write("t", octets("go\r\n"))
                    first  <- client.readLine("t", 100)
                    second <- client.readLine("t", 100)
                yield assert(text(first) == "a\rb" && text(second) == "c")
            }
        }
        "a line longer than the limit is Protocol, keeping its first limit octets escaped for the session to redact" in scripted {
            Scope.run {
                for
                    port   <- serveInPieces("x" * 245 + "\u0001" * 65)
                    client <- open(port)
                    _      <- client.write("t", octets("go\r\n"))
                    ex     <- failure(client.readLine("fetch", 250))
                yield ex.kind match
                    case Kind.Protocol(received) =>
                        assert(ex.method == "fetch" && ex.host == "127.0.0.1" && ex.port == port)
                        assert(received == "x" * 245 + "\\x01" * 5)
                    case other => fail(other.toString)
            }
        }
    }

    "a line longer than the limit is Protocol even when its line end is already buffered with it" in scripted {
        // Unsafe: the in-memory connection pair is unsafe-tier; the whole line, its line end and the next line go in as one span before the
        // first read, so the line end is buffered when the limit is checked, whatever chunking a socket would do.
        Sync.Unsafe.defer {
            val (client, server) = kyo.net.internal.transport.Connection.inMemoryPair()
            discard(server.outbound.offer(Span.from(("y" * 300 + "\r\n* NEXT\r\n").getBytes("US-ASCII"))))
            client
        }.map(LineConnection.wrap(_, "127.0.0.1", 0)).map(line => failure(line.readLine("fetch", 250))).map { ex =>
            ex.kind match
                case Kind.Protocol(received) => assert(received == "y" * 250)
                case other                   => fail(other.toString)
        }
    }

    "counted octets" - {
        "a literal across writes is read exactly, and the line after it follows" in scripted {
            Scope.run {
                for
                    port    <- serveInPieces("ab", "cdef\r\n* AFTER\r\n")
                    client  <- open(port)
                    _       <- client.write("t", octets("go\r\n"))
                    _       <- client.write("t", octets("go\r\n"))
                    literal <- client.readExactly("t", 5)
                    rest    <- client.readLine("t", 100)
                    after   <- client.readLine("t", 100)
                yield assert(text(literal) == "abcde" && text(rest) == "f" && text(after) == "* AFTER")
            }
        }
    }

    "failures" - {
        "the peer closing is ConnectionClosed, naming the operation, host and port" in scripted {
            Scope.run {
                for
                    port   <- listen()(server => server.close)
                    client <- open(port)
                    ex     <- failure(client.readLine("status", 100))
                yield
                    assert(ex.kind == Kind.ConnectionClosed(Absent))
                    assert(ex.method == "status" && ex.port == port)
            }
        }
        "a refused connect is Connect, keeping kyo-net's cause" in scripted {
            Scope.run(listen()(_ => ())).map(closedPort => refused(open(closedPort))).map { ex =>
                assert(ex.kind == ConnectKind.Connect)
                assert(ex.cause.exists(_.isInstanceOf[kyo.net.NetConnectException]))
                assert(ex.getCause != null)
            }
        }
        "a server that never completes the TLS handshake is ConnectTimeout after the deadline, on kyo's clock" in scripted {
            // The server accepting proves the client's connect is past TCP and waiting on the handshake, but not that its deadline is
            // armed: the accept runs on kyo-net's thread, the deadline on the client's fiber. Virtual time moves only once the deadline's
            // sleep is pending, so the deadline and not the connect's own progress decides the outcome.
            Scope.run {
                Latch.init(1).map { accepted =>
                    listen()(server => accepted.release.andThen(server.readLine("fixture", 100))).map { port =>
                        Clock.withTimeControl { control =>
                            Fiber.initUnscoped(refused(open(port, Present(NetTlsConfig(trustAll = true)), 30.seconds))).map { fiber =>
                                accepted.await.andThen(control.awaitPendingSleepers(1)).andThen(control.advance(31.seconds))
                                    .andThen(fiber.get).map { ex =>
                                        assert(ex.kind == ConnectKind.ConnectTimeout)
                                        assert(ex.timeout == Present(30.seconds))
                                    }
                            }
                        }
                    }
                }
            }
        }
    }

    "TLS" - {
        "implicit TLS to a certificate the configured CA file trusts" in scripted {
            Scope.run {
                for
                    server <- serverTls(Certificate.Localhost)
                    port   <- listen(Present(server))(line => line.write("fixture", octets("* OK tls\r\n")))
                    ca     <- pems(Certificate.Localhost)
                    client <- open(port, Present(NetTlsConfig(caCertPath = Present(ca._1))))
                    line   <- client.readLine("t", 100)
                yield assert(text(line) == "* OK tls")
            }
        }
        "a certificate for another host is Tls, even when its chain is trusted" in scripted {
            Scope.run {
                for
                    server <- serverTls(Certificate.WrongHost)
                    port   <- listen(Present(server))(line => line.write("fixture", octets("* OK tls\r\n")))
                    ca     <- pems(Certificate.WrongHost)
                    ex     <- refused(open(port, Present(NetTlsConfig(caCertPath = Present(ca._1)))))
                yield
                    assert(ex.kind == ConnectKind.Tls)
                    assert(ex.cause.exists(_.isInstanceOf[kyo.net.NetTlsHandshakeException]))
            }
        }
        "STARTTLS upgrades in place, and a line the server sent before the upgrade is never read" in scripted {
            Scope.run {
                for
                    server <- serverTls(Certificate.Localhost)
                    port   <- listen() { line =>
                        line.readLine("fixture", 100)
                            .andThen(line.write("fixture", octets("A1 OK begin TLS\r\n* INJECTED\r\n")))
                            .andThen(line.startTls("fixture", server, 5.seconds))
                            .andThen(line.write("fixture", octets("* OK after\r\n")))
                    }
                    ca     <- pems(Certificate.Localhost)
                    client <- open(port)
                    _      <- client.write("t", octets("A1 STARTTLS\r\n"))
                    ok     <- client.readLine("t", 100)
                    _      <- client.startTls("t", NetTlsConfig(caCertPath = Present(ca._1)), 5.seconds)
                    after  <- client.readLine("t", 100)
                yield assert(text(ok) == "A1 OK begin TLS" && text(after) == "* OK after")
            }
        }
    }

end LineConnectionTest
