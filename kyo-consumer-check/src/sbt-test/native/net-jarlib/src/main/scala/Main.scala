import kyo.*
import kyo.net.*

// A full TLS round trip over loopback: a TLS listener serving the fixture's self-signed localhost certificate sends a
// greeting, and a TLS client reads it back. Succeeds only when this build linked a TLS engine into kyo-net's shims.
object Main extends KyoApp:
    import AllowUnsafe.embrace.danger

    run {
        val server = NetTlsConfig(certChainPath = Present("server.pem"), privateKeyPath = Present("server.key"))
        for
            listener <- NetPlatform.transport.listenTls("127.0.0.1", port = 0, backlog = 16, tls = server) { conn =>
                val _ = conn.outbound.offer(Span.fromUnsafe("hello".getBytes))
            }.safe.get
            tls <- Abort.run[Any] {
                NetPlatform.transport.connectTls("127.0.0.1", listener.port, NetTlsConfig(trustAll = true)).safe.get.map { conn =>
                    conn.inbound.safe.take.map { reply =>
                        conn.close()
                        new String(reply.toArray)
                    }
                }
            }
            _ <- Sync.defer(listener.close())
            outcome = tls match
                case Result.Success(value) => value
                case Result.Failure(error) => s"failure:${error.getClass.getSimpleName}"
                case Result.Panic(error)   => s"panic:${error.getClass.getSimpleName}"
            _ <- Console.printLine(s"CONSUMER tls=$outcome")
        yield ()
    }
end Main
