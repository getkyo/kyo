import kyo.*
import kyo.net.*

// A TLS round trip over loopback per native provider: a TLS listener serving the fixture's self-signed localhost certificate sends a
// greeting, and a TLS client reads it back. Each round trip pins its provider on both ends, so `boringssl` answers only from the library
// the artifact delivers and `openssl` only from the machine's own OpenSSL, and neither can be served by the other.
object Main extends KyoApp:
    import AllowUnsafe.embrace.danger

    private def roundTrip(provider: String)(using Frame): String < (Async & Scope) =
        val server = NetTlsConfig(certChainPath = Present("server.pem"), privateKeyPath = Present("server.key"), tlsProvider = Present(provider))
        val client = NetTlsConfig(trustAll = true, tlsProvider = Present(provider))
        Abort.run[Any] {
            NetPlatform.transport.listenTls("127.0.0.1", port = 0, backlog = 16, tls = server) { conn =>
                val _ = conn.outbound.offer(Span.fromUnsafe("hello".getBytes))
            }.safe.get.map { listener =>
                Sync.ensure(Sync.defer(listener.close())) {
                    NetPlatform.transport.connectTls("127.0.0.1", listener.port, client).safe.get.map { conn =>
                        conn.inbound.safe.take.map { reply =>
                            conn.close()
                            new String(reply.toArray)
                        }
                    }
                }
            }
        }.map {
            case Result.Success(value) => value
            case Result.Failure(error) => s"failure:${error.getClass.getSimpleName}"
            case Result.Panic(error)   => s"panic:${error.getClass.getSimpleName}"
        }
    end roundTrip

    run {
        for
            boringssl <- roundTrip("boringssl")
            openssl   <- roundTrip("openssl")
            _         <- Console.printLine(s"CONSUMER tls.boringssl=$boringssl tls.openssl=$openssl")
        yield ()
    }
end Main
