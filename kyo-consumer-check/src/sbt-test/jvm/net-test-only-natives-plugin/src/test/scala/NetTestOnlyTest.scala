import kyo.*
import kyo.net.*

// A TLS round trip over loopback, with the backend and TLS provider forced to the natives by the build's javaOptions.
class NetTestOnlyTest extends munit.FunSuite:

    test("a test-only dependency's natives load in the test run") {
        import AllowUnsafe.embrace.danger
        val server = NetTlsConfig(certChainPath = Present("server.pem"), privateKeyPath = Present("server.key"))
        val roundTrip =
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
        assertEquals(KyoApp.Unsafe.runAndBlock(60.seconds)(roundTrip), Result.succeed("hello"))
    }
end NetTestOnlyTest
