package kyo.internal

import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import kyo.*

class NioHandleTest extends kyo.Test:

    import AllowUnsafe.embrace.danger

    /** Open a connected SocketChannel pair for testing. Returns (client, server). The caller is responsible for closing both. */
    def openLoopbackPair(): (SocketChannel, SocketChannel) =
        val serverSock = ServerSocketChannel.open()
        serverSock.bind(new InetSocketAddress("127.0.0.1", 0))
        val port   = serverSock.socket().getLocalPort
        val client = SocketChannel.open()
        client.configureBlocking(false)
        client.connect(new InetSocketAddress("127.0.0.1", port))
        // Accept blocks briefly for loopback
        serverSock.configureBlocking(true)
        val server = serverSock.accept()
        client.finishConnect()
        serverSock.close()
        (client, server)
    end openLoopbackPair

    "init creates plain TCP handle with Absent tls" in {
        val (client, server) = openLoopbackPair()
        try
            val handle = NioHandle.init(client, 4096)
            assert(handle.channel eq client)
            assert(handle.tls == Absent)
            assert(handle.readBufferSize == 4096)
            succeed
        finally
            client.close()
            server.close()
        end try
    }

    "readBuffer is a direct ByteBuffer" in {
        val (client, server) = openLoopbackPair()
        try
            val handle = NioHandle.init(client, 4096)
            assert(handle.readBuffer.isDirect)
            succeed
        finally
            client.close()
            server.close()
        end try
    }

    "readBuffer capacity matches bufferSize parameter" in {
        val (client, server) = openLoopbackPair()
        try
            val handle = NioHandle.init(client, 4096)
            assert(handle.readBuffer.capacity() == 4096)
            succeed
        finally
            client.close()
            server.close()
        end try
    }

    "DefaultReadBufferSize is 8192" in {
        assert(NioHandle.DefaultReadBufferSize == 8192)
        succeed
    }

    "initTls creates handle with Present tls state" in {
        val (client, server) = openLoopbackPair()
        try
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, null, null)
            val engine = ctx.createSSLEngine()
            val handle = NioHandle.initTls(client, 4096, engine)
            handle.tls match
                case Present(state) => assert(state.engine eq engine)
                case Absent         => fail("expected Present tls state")
            succeed
        finally
            client.close()
            server.close()
        end try
    }

    "TLS buffers sized from SSLEngine session packet/application sizes" in {
        val (client, server) = openLoopbackPair()
        try
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, null, null)
            val engine  = ctx.createSSLEngine()
            val session = engine.getSession
            val handle  = NioHandle.initTls(client, 4096, engine)
            handle.tls match
                case Present(state) =>
                    assert(state.netInBuf.capacity() == session.getPacketBufferSize)
                    assert(state.netOutBuf.capacity() == session.getPacketBufferSize)
                    assert(state.appInBuf.capacity() == session.getApplicationBufferSize)
                case Absent =>
                    fail("expected Present tls state")
            end match
            succeed
        finally
            client.close()
            server.close()
        end try
    }

    "close plain TCP handle closes the channel" in {
        val (client, server) = openLoopbackPair()
        try
            val handle = NioHandle.init(client, 4096)
            NioHandle.close(handle)
            assert(!client.isOpen)
            succeed
        finally
            // client already closed by NioHandle.close; close server
            server.close()
        end try
    }

    "close TLS handle calls engine closeOutbound then closes channel" in {
        val (client, server) = openLoopbackPair()
        try
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, null, null)
            val engine = ctx.createSSLEngine()
            val handle = NioHandle.initTls(client, 4096, engine)
            NioHandle.close(handle)
            // After close, engine status should reflect outbound close
            assert(engine.isOutboundDone)
            assert(!client.isOpen)
            succeed
        finally
            server.close()
        end try
    }

    "close is idempotent — second close does not throw" in {
        val (client, server) = openLoopbackPair()
        try
            val handle = NioHandle.init(client, 4096)
            NioHandle.close(handle)
            NioHandle.close(handle) // Must not throw
            succeed
        finally
            server.close()
        end try
    }

end NioHandleTest
