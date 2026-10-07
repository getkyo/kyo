package kyo.net.internal

import java.nio.ByteBuffer
import kyo.*
import kyo.net.Connection as NetConnection
import kyo.net.NetTlsConfig

/** A TLS client that sends its first flight over a plaintext connection and then stalls, so the server's accept handshake answers and parks
  * waiting for a second flight that never comes.
  */
object StalledTlsClient:

    /** The first flight of a JDK client handshake: one ClientHello record. Empty only if the engine produced nothing. */
    def clientHello(port: Int)(using AllowUnsafe, Frame): Span[Byte] =
        val engine = NioTransport.createSslContext(NetTlsConfig(trustAll = true), isServer = false).createSSLEngine("127.0.0.1", port)
        engine.setUseClientMode(true)
        engine.beginHandshake()
        val out = ByteBuffer.allocate(engine.getSession.getPacketBufferSize)
        discard(engine.wrap(ByteBuffer.allocate(0), out))
        out.flip()
        val arr = new Array[Byte](out.remaining())
        out.get(arr)
        Span.fromUnsafe(arr)
    end clientHello

    /** Take until the inbound terminates. Leftover server handshake bytes are skipped, so only a closed channel ends the loop. */
    def awaitInboundClosed(client: NetConnection)(using Frame): Unit < Async =
        Loop(()) { _ =>
            Abort.run[Closed](client.inbound.safe.take).map {
                case Result.Success(_) => Loop.continue(())
                case _                 => Loop.done(())
            }
        }

end StalledTlsClient
