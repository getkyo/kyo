package kyo.internal.email.net

import kyo.*
import kyo.internal.charset.Utf8
import kyo.net.Connection
import kyo.net.NetException
import kyo.net.NetPlatform
import kyo.net.NetTlsConfig
import kyo.net.TlsTestCertShared

/** A loopback server on 127.0.0.1 for the mail protocol tests: each accepted connection runs `handler` on its own fiber, read and written
  * through a [[LineConnection]] in the server role. The listener and every accepted connection are closed when the enclosing `Scope` ends,
  * so a handler that never returns leaks nothing (the shape of kyo-sql's `FakeServer`).
  */
object LineConnectionFixture:

    /** Where the server's TLS material comes from: the localhost certificate, or the one issued to `wronghost.example`. */
    enum Certificate derives CanEqual:
        case Localhost, WrongHost

    /** The server's certificate and key paths, written once per call. */
    def pems(certificate: Certificate)(using Frame): (String, String) < Sync =
        certificate match
            case Certificate.Localhost => TlsTestCertShared.writePems
            case Certificate.WrongHost => TlsTestCertShared.writeWrongHostPems

    /** The server's TLS configuration for `certificate`, with `adjust` applied: a required client certificate or a pinned version. */
    def serverTls(certificate: Certificate, adjust: NetTlsConfig => NetTlsConfig = identity)(using Frame): NetTlsConfig < Sync =
        pems(certificate).map((cert, key) => adjust(NetTlsConfig(certChainPath = Present(cert), privateKeyPath = Present(key))))

    /** A server that requires a client certificate, trusting its own self-signed localhost certificate as the client CA, so a client
      * presenting that same certificate is accepted.
      */
    def requiringClientCertificate(tls: NetTlsConfig): NetTlsConfig =
        tls.copy(caCertPath = tls.certChainPath, clientAuth = NetTlsConfig.ClientAuth.Required)

    /** The localhost certificate and key as a client certificate, the one `requiringClientCertificate` accepts. */
    def clientCertificate(using Frame): Email.Tls.ClientCertificate < Sync =
        pems(Certificate.Localhost).map((cert, key) => Email.Tls.ClientCertificate(Path(cert), Path(key)))

    /** What a server script can fail with: the connection closing under it, or its STARTTLS upgrade failing. */
    type Failure = EmailTransportException | EmailConnectException

    /** A leaf body on kyo's controlled clock, so the deadlines a scripted exchange arms never fire in real time. It wraps the body inside
      * the leaf rather than through `aroundLeaf`: the runner times the leaf inside `aroundLeaf`, and its timeout must stay on the real
      * clock, where a stall still fails and a leaf that advances time past the timeout does not.
      */
    def scripted[A, S](body: => A < S)(using Frame): A < (Sync & S) = Clock.withTimeControl(_ => body)

    /** Listens in plaintext, or over TLS from the first byte with `tls`, and answers with the bound port. Each handler runs on the clock
      * `listen` was called under.
      */
    def listen(tls: Maybe[NetTlsConfig] = Absent)(handler: LineConnection => Any < (Async & Abort[Failure]))(using
        Frame
    ): Int < (Async & Scope & Abort[NetException]) =
        AtomicRef.initWith(Chunk.empty[Connection]) { accepted =>
            AtomicRef.initWith(Chunk.empty[LineConnection]) { lines =>
                Clock.use { clock =>
                    // Unsafe: kyo-net's listen is unsafe-tier; its accept callback is synchronous, so each handler is spawned on a fiber
                    // of its own, which does not inherit the caller's clock.
                    Sync.Unsafe.defer {
                        def accept(conn: Connection): Unit =
                            discard(accepted.unsafe.updateAndGet(_.append(conn)))
                            discard(Fiber.Unsafe.init(
                                Clock.let(clock)(LineConnection.wrap(conn, "127.0.0.1", 0).map { line =>
                                    lines.updateAndGet(_.append(line)).andThen(Abort.run[Failure](handler(line)).unit)
                                })
                            ))
                        end accept
                        tls match
                            case Present(config) => NetPlatform.transport.listenTls("127.0.0.1", 0, 128, config)(accept)
                            case Absent          => NetPlatform.transport.listen("127.0.0.1", 0, 128)(accept)
                    }
                }.map(_.safe).map(_.get).map { listener =>
                    // A leaf that connects to the port after the scope expects a refusal, and close returns before the descriptor is
                    // released, so the finalizer waits for `released`. Node releases the listener only once every accepted connection has
                    // ended, so each one is closed first: the accepted connection, and the line's current one, which a STARTTLS upgrade
                    // replaced with the TLS connection.
                    // Unsafe: closing the listener and the accepted connections is unsafe-tier; close is idempotent.
                    Scope.ensure(
                        Sync.Unsafe.defer {
                            listener.close()
                            accepted.unsafe.get().foreach(conn => conn.close())
                        }.andThen(lines.get.map(Kyo.foreachDiscard(_)(_.close)))
                            .andThen(Sync.Unsafe.defer(listener.released.safe).map(_.get))
                    ).andThen(listener.port)
                }
            }
        }
    end listen

    /** The line as text, ASCII only as the test scripts write it. */
    def text(octets: Span[Byte]): String = Utf8.decode(octets)

    def octets(text: String): Span[Byte] = Utf8.encode(text)

end LineConnectionFixture
