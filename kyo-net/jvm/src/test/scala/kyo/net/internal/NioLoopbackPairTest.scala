package kyo.net.internal

import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.nio.channels.ServerSocketChannel
import kyo.*
import kyo.net.Test

class NioLoopbackPairTest extends Test:

    "the two ends are connected to each other" in {
        NioLoopbackPair.open().map { (client, server) =>
            try
                assert(client.isConnected)
                assert(server.isConnected)
                assert(!client.isBlocking)
                assert(server.isBlocking)
                assert(client.getRemoteAddress.equals(server.getLocalAddress))
                assert(client.getLocalAddress.equals(server.getRemoteAddress))
            finally
                client.close()
                server.close()
            end try
        }
    }

    "socket options set by the configure hooks are in place" in {
        NioLoopbackPair.open(
            configureListener = _.setOption(java.net.StandardSocketOptions.SO_RCVBUF, Integer.valueOf(4096)): Unit,
            configureClient = _.setOption(java.net.StandardSocketOptions.TCP_NODELAY, java.lang.Boolean.TRUE): Unit
        ).map { (client, server) =>
            try assert(client.getOption(java.net.StandardSocketOptions.TCP_NODELAY).booleanValue)
            finally
                client.close()
                server.close()
            end try
        }
    }

    // The target is a port whose listener just closed, so the connect is refused. A non-blocking connect reports that refusal only through
    // finishConnect (on Windows, after its SYN retries), the shape a TIME_WAIT 4-tuple collision takes, and the pair's listener never sees a
    // connection: a setup that waits on the accept first never returns here. A bound socket that never listens would hold the port, but
    // macOS drops a SYN to it instead of resetting, which turns the refusal into a 75s SYN timeout.
    "a connect that fails is reported, not waited on at the accept" in {
        val listener = ServerSocketChannel.open()
        val closed   = ServerSocketChannel.open()
        listener.bind(new InetSocketAddress("127.0.0.1", 0))
        listener.configureBlocking(false)
        closed.bind(new InetSocketAddress("127.0.0.1", 0))
        val target = closed.getLocalAddress
        closed.close()
        Sync.ensure(Sync.defer(listener.close())) {
            Abort.run[IOException](NioLoopbackPair.connectTo(listener, target)).map {
                case Result.Failure(e) => assert(e.isInstanceOf[ConnectException], s"expected a refused connect, got $e")
                case other             => fail(s"expected the refused connect to fail the pair, got $other")
            }
        }
    }

end NioLoopbackPairTest
