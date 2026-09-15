package linkcheck

// Outside the kyo package, as an application is.
import kyo.*
import kyo.net.*

/** Listens on localhost, connects to itself, and echoes one message: the path from the kyo-net transport to Node's sockets. */
object NetEcho extends KyoApp:
    import AllowUnsafe.embrace.danger

    private def echoServer(transport: Transport)(using Frame): Listener < (Async & Abort[NetException]) =
        transport.listen("127.0.0.1", 0, 16) { serverConn =>
            val _ = Sync.Unsafe.evalOrThrow {
                Fiber.initUnscoped {
                    Abort.run[Closed](serverConn.inbound.safe.take.map(bytes => serverConn.outbound.safe.put(bytes))).unit
                }
            }
        }.safe.get

    run {
        Abort.run[Any] {
            val transport = NetPlatform.transport
            echoServer(transport).map { listener =>
                transport.connect("127.0.0.1", listener.port).safe.get.map { conn =>
                    conn.outbound.safe.put(Span.fromUnsafe("kyo".getBytes("UTF-8"))).andThen {
                        conn.inbound.safe.take.map { bytes =>
                            conn.close()
                            listener.close()
                            s"echo ${new String(bytes.toArray, "UTF-8")}"
                        }
                    }
                }
            }
        }.map(result => Console.printLine(Report(result)))
    }
end NetEcho
