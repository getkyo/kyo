import kyo.*
import kyo.net.*
import scala.scalajs.js

// Plain TCP and a TLS round trip over loopback on Node, writing the outcome to out.txt for the scripted test.
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
                js.Dynamic.global.require("fs").writeFileSync("out.txt", s"CONSUMER plain=$plain tls=$tls\n")
            }
        yield ()
    }

    private def roundTrip(connect: Connection < (Async & Abort[NetException]))(using Frame): String < Async =
        Abort.run[Any](connect.map(conn => conn.inbound.safe.take.map { reply => conn.close(); new String(reply.toArray) })).map {
            case Result.Success(value) => value
            case Result.Failure(error) => s"failure:${error.getClass.getSimpleName}"
            case Result.Panic(error)   => s"panic:${error.getClass.getSimpleName}"
        }
end Main
