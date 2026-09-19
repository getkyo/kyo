import kyo.*
import kyo.net.*

// Reads a greeting back over plain TCP on a loopback listener, then attempts TLS against the same listener. The
// TLS attempt keeps every TLS shim reachable, so the link has to resolve them; its outcome shows which TLS engine
// this build linked. Loopback only, so the check never depends on the network.
object Main extends KyoApp:
    import AllowUnsafe.embrace.danger

    run {
        for
            listener <- NetPlatform.transport.listen("127.0.0.1", port = 0, backlog = 16) { conn =>
                discard(conn.outbound.offer(Span.fromUnsafe("hello".getBytes)))
            }.safe.get
            plain <- Abort.run[Any] {
                NetPlatform.transport.connect("127.0.0.1", listener.port).safe.get.map { conn =>
                    conn.inbound.safe.take.map { reply =>
                        conn.close()
                        new String(reply.toArray)
                    }
                }
            }
            tls <- Abort.run[Any] {
                NetPlatform.transport.connectTls("127.0.0.1", listener.port, NetTlsConfig(trustAll = true)).safe.get.map { conn =>
                    conn.close()
                    "connected"
                }
            }
            _ <- Sync.defer(listener.close())
            _ <- Console.printLine(s"CONSUMER plain=${describe(plain)} tls=${describe(tls)}")
        yield ()
    }

    private def describe(result: Result[Any, String]): String =
        result match
            case Result.Success(value) => value
            case Result.Failure(error) => s"failure:${error.getClass.getSimpleName}"
            case Result.Panic(error)   => s"panic:${error.getClass.getSimpleName}"
end Main
