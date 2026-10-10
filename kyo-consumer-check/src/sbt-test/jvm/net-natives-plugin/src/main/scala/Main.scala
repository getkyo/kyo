import kyo.*
import kyo.net.*

// A TLS round trip over loopback with the backend and TLS provider forced to the natives, writing its outcome to
// out.txt for the scripted test to check.
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
            _ <- Sync.defer(java.nio.file.Files.writeString(java.nio.file.Path.of("out.txt"), s"CONSUMER tls=$outcome\n"))
        yield ()
    }
end Main
