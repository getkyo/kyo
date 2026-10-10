import kyo.*
import kyo.net.*

// Plain TCP and a TLS round trip over loopback on Node, over the transport the delivered natives select. The
// transport's own name goes into the outcome, so a run that quietly fell back to Node's transport fails the test
// instead of passing on the floor.
//
// The outcome goes to stdout rather than to a file, unlike the CommonJS fixture beside this one: writing the file
// there uses the global `require` to reach `fs`, and this build has no global `require` to reach anything with.
//
// Nothing closes the transport: it is process-lifetime, exactly as an application leaves it.
object Main extends KyoApp:
    import AllowUnsafe.embrace.danger

    run {
        val server = NetTlsConfig(certChainPath = Present("server.pem"), privateKeyPath = Present("server.key"))
        for
            plainListener <- NetPlatform.transport.listen("127.0.0.1", port = 0, backlog = 16) { conn =>
                val _ = conn.outbound.offer(Span.fromUnsafe("hello".getBytes))
            }.safe.get
            plain <- roundTrip(NetPlatform.transport.connect("127.0.0.1", plainListener.port).safe.get)
            tlsListener <- NetPlatform.transport.listenTls("127.0.0.1", port = 0, backlog = 16, tls = server) { conn =>
                val _ = conn.outbound.offer(Span.fromUnsafe("hello".getBytes))
            }.safe.get
            tls <- roundTrip(NetPlatform.transport.connectTls("127.0.0.1", tlsListener.port, NetTlsConfig(trustAll = true)).safe.get)
            _ <- Sync.defer {
                plainListener.close()
                tlsListener.close()
            }
            transport = NetPlatform.transport.getClass.getSimpleName
            _ <- Console.printLine(s"CONSUMER transport=$transport plain=$plain tls=$tls")
        yield ()
    }

    private def roundTrip(connect: Connection < (Async & Abort[NetException]))(using Frame): String < Async =
        Abort.run[Any](connect.map(conn => conn.inbound.safe.take.map { reply => conn.close(); new String(reply.toArray) })).map {
            case Result.Success(value) => value
            case Result.Failure(error) => s"failure:${error.getClass.getSimpleName}"
            case Result.Panic(error)   => s"panic:${error.getClass.getSimpleName}"
        }
end Main
