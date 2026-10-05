package kyo.net.internal

import java.nio.channels.SocketChannel
import javax.net.ssl.SSLContext
import kyo.*
import kyo.net.Test

class NioHandleTest extends Test:

    import AllowUnsafe.embrace.danger

    // A handle only holds its channel and closes it, so these leaves need no peer. A loopback connection here made them depend on the
    // host's ephemeral ports: on Windows a connect whose 4-tuple matches one still in TIME_WAIT fails with WSAEADDRINUSE, and a setup
    // waiting on accept never returns.
    "init creates plain TCP handle with Absent tls" in {
        val channel = SocketChannel.open()
        try
            val handle = NioHandle.init(channel, 4096, Duration.Infinity, Duration.Infinity, Frame.internal)
            assert(handle.channel eq channel)
            assert(handle.tls == Absent)
            assert(handle.readBufferSize == 4096)
            succeed
        finally channel.close()
        end try
    }

    "readBuffer is a direct ByteBuffer" in {
        val channel = SocketChannel.open()
        try
            val handle = NioHandle.init(channel, 4096, Duration.Infinity, Duration.Infinity, Frame.internal)
            assert(handle.readBuffer.isDirect)
            succeed
        finally channel.close()
        end try
    }

    "readBuffer capacity matches bufferSize parameter" in {
        val channel = SocketChannel.open()
        try
            val handle = NioHandle.init(channel, 4096, Duration.Infinity, Duration.Infinity, Frame.internal)
            assert(handle.readBuffer.capacity() == 4096)
            succeed
        finally channel.close()
        end try
    }

    "DefaultReadBufferSize is 8192" in {
        assert(NioHandle.DefaultReadBufferSize == 8192)
        succeed
    }

    "initTls creates handle with Present tls state" in {
        val channel = SocketChannel.open()
        try
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, null, null)
            val engine = ctx.createSSLEngine()
            val handle = NioHandle.initTls(channel, 4096, engine, Duration.Infinity, Duration.Infinity, Frame.internal)
            handle.tls match
                case Present(state) => assert(state.engine eq engine)
                case Absent         => fail("expected Present tls state")
            succeed
        finally channel.close()
        end try
    }

    "TLS buffers sized from SSLEngine session packet/application sizes" in {
        val channel = SocketChannel.open()
        try
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, null, null)
            val engine  = ctx.createSSLEngine()
            val session = engine.getSession
            val handle  = NioHandle.initTls(channel, 4096, engine, Duration.Infinity, Duration.Infinity, Frame.internal)
            handle.tls match
                case Present(state) =>
                    assert(state.netInBuf.capacity() == session.getPacketBufferSize)
                    assert(state.netOutBuf.capacity() == session.getPacketBufferSize)
                    assert(state.appInBuf.capacity() == session.getApplicationBufferSize)
                case Absent =>
                    fail("expected Present tls state")
            end match
            succeed
        finally channel.close()
        end try
    }

    "close plain TCP handle closes the channel" in {
        val channel = SocketChannel.open()
        val handle  = NioHandle.init(channel, 4096, Duration.Infinity, Duration.Infinity, Frame.internal)
        NioHandle.close(handle)
        assert(!channel.isOpen)
        succeed
    }

    "close TLS handle calls engine closeOutbound then closes channel" in {
        val channel = SocketChannel.open()
        val ctx     = SSLContext.getInstance("TLS")
        ctx.init(null, null, null)
        val engine = ctx.createSSLEngine()
        val handle = NioHandle.initTls(channel, 4096, engine, Duration.Infinity, Duration.Infinity, Frame.internal)
        NioHandle.close(handle)
        assert(engine.isOutboundDone)
        assert(!channel.isOpen)
        succeed
    }

    "close is idempotent: second close does not throw" in {
        val channel = SocketChannel.open()
        val handle  = NioHandle.init(channel, 4096, Duration.Infinity, Duration.Infinity, Frame.internal)
        NioHandle.close(handle)
        NioHandle.close(handle)
        assert(!channel.isOpen)
        succeed
    }

end NioHandleTest
