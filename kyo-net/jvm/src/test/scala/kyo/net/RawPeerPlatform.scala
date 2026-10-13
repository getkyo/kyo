package kyo.net

import java.net.InetSocketAddress
import java.net.StandardSocketOptions
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import kyo.*

/** [[RawPeer]] on the JVM: a blocking `java.nio` socket channel, the one JDK socket that sets `SO_LINGER` on every OS. Each peer runs its
  * blocking calls on one daemon thread of its own and settles a promise per call, so no kyo carrier blocks; closing the channel wakes a
  * call still blocked in it.
  */
private[net] object RawPeerPlatform:

    import AllowUnsafe.embrace.danger

    def connect(port: Int, rcvBuf: Maybe[Int])(using Frame): RawPeer < (Async & Scope) =
        Sync.defer {
            val channel = SocketChannel.open()
            rcvBuf.foreach(n => discard(channel.setOption(StandardSocketOptions.SO_RCVBUF, Integer.valueOf(n))))
            channel
        }.map { channel =>
            val peer = new NioPeer(channel)
            Scope.ensure(peer.close()).andThen(peer.run(discard(channel.connect(new InetSocketAddress("127.0.0.1", port))))).andThen(peer)
        }

    def listen(rcvBuf: Maybe[Int])(using Frame): RawPeer.Listener < (Async & Scope) =
        Sync.defer {
            val server = ServerSocketChannel.open()
            rcvBuf.foreach(n => discard(server.setOption(StandardSocketOptions.SO_RCVBUF, Integer.valueOf(n))))
            discard(server.bind(new InetSocketAddress("127.0.0.1", 0)))
            server
        }.map { server =>
            val acceptor = daemon()
            Scope.ensure(Sync.defer { server.close(); acceptor.shutdownNow(); () }).andThen {
                new RawPeer.Listener:
                    val port                                             = server.getLocalAddress.asInstanceOf[InetSocketAddress].getPort
                    def accept()(using Frame): RawPeer < (Async & Scope) =
                        onThread(acceptor)(server.accept()).map { channel =>
                            val peer = new NioPeer(channel)
                            Scope.ensure(peer.close()).andThen(peer)
                        }
            }
        }

    private def daemon(): ExecutorService =
        Executors.newSingleThreadExecutor { task =>
            val thread = new Thread(task, "kyo-net-raw-peer")
            thread.setDaemon(true)
            thread
        }

    /** Runs a blocking call on `thread`, settling with its result or its error. */
    private def attempt[A](thread: ExecutorService)(call: => A)(using Frame): Result[Throwable, A] < Async =
        val promise = Promise.Unsafe.init[Result[Throwable, A], Any]()
        discard(thread.submit((() => promise.completeDiscard(Result.succeed(Result.catching[Throwable](call)))): Runnable))
        promise.safe.get
    end attempt

    private def onThread[A](thread: ExecutorService)(call: => A)(using Frame): A < Async =
        attempt(thread)(call).map(_.getOrThrow)

    final private class NioPeer(channel: SocketChannel) extends RawPeer:

        private val thread                               = daemon()
        @volatile private var ended: Maybe[RawPeer.Read] = Absent

        def run[A](call: => A)(using Frame): A < Async = onThread(thread)(call)

        def send(bytes: Span[Byte])(using Frame): Unit < Async =
            run {
                val buffer = ByteBuffer.wrap(bytes.toArray)
                while buffer.hasRemaining do discard(channel.write(buffer))
            }

        def recv()(using Frame): RawPeer.Read < Async =
            ended match
                case Present(end) => end
                case Absent       =>
                    val buffer = ByteBuffer.allocate(64 * 1024)
                    attempt(thread)(channel.read(buffer)).map {
                        case Result.Success(n) if n < 0 =>
                            ended = Present(RawPeer.Read.Fin)
                            RawPeer.Read.Fin
                        case Result.Success(n) =>
                            RawPeer.Read.Bytes(Span.fromUnsafe(java.util.Arrays.copyOf(buffer.array(), n)))
                        case Result.Error(e) =>
                            val reset = RawPeer.Read.Reset(String.valueOf(e))
                            ended = Present(reset)
                            reset
                    }

        def shutdownWr()(using Frame): Unit < Async = Sync.defer(discard(channel.shutdownOutput()))

        def reset()(using Frame): Unit < Async =
            Sync.defer {
                discard(channel.setOption(StandardSocketOptions.SO_LINGER, Integer.valueOf(0)))
                channel.close()
            }

        def close()(using Frame): Unit < Async =
            Sync.defer {
                channel.close()
                discard(thread.shutdown())
            }
    end NioPeer

end RawPeerPlatform
