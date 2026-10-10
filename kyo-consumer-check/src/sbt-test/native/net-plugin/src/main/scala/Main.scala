import kyo.*
import kyo.net.*

// A full TLS round trip over loopback: a TLS listener serving the fixture's self-signed localhost certificate sends a
// greeting, and a TLS client reads it back. Succeeds only when this build linked a TLS engine into kyo-net's shims; where
// it did not, the listener's own TLS setup is what fails, and the outcome names the failure instead of the process dying.
object Main extends KyoApp:
    import AllowUnsafe.embrace.danger

    run {
        val server = NetTlsConfig(certChainPath = Present("server.pem"), privateKeyPath = Present("server.key"))
        Abort.run[Any] {
            NetPlatform.transport.listenTls("127.0.0.1", port = 0, backlog = 16, tls = server) { conn =>
                val _ = conn.outbound.offer(Span.fromUnsafe("hello".getBytes))
            }.safe.get.map { listener =>
                Sync.ensure(Sync.defer(listener.close())) {
                    NetPlatform.transport.connectTls("127.0.0.1", listener.port, NetTlsConfig(trustAll = true)).safe.get.map { conn =>
                        conn.inbound.safe.take.map { reply =>
                            conn.close()
                            new String(reply.toArray)
                        }
                    }
                }
            }
        }.map { tls =>
            val outcome = tls match
                case Result.Success(value) => value
                case Result.Failure(error) => s"failure:${error.getClass.getSimpleName}"
                case Result.Panic(error)   => s"panic:${error.getClass.getSimpleName}"
            Console.printLine(s"CONSUMER tls=$outcome")
        }
    }
end Main
