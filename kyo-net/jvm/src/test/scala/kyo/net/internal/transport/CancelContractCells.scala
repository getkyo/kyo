package kyo.net.internal.transport

import java.io.IOException
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import kyo.*
import kyo.net.internal.NioHandle
import kyo.net.internal.NioIoDriver
import kyo.net.internal.NioLoopbackPair
import kyo.net.internal.posix.PosixCancelCells

/** The JVM's [[CancelCell]]s: the posix drivers plus the NIO floor. */
object CancelContractCells:

    val all: Seq[CancelCell] = PosixCancelCells.all :+ Nio

    /** The NIO driver over real loopback channels. It has no `awaitAccept` (listeners use a ServerSocketChannel seam of their own) and
      * closes channels, not descriptors it could count.
      */
    object Nio extends CancelCell:
        import CancelCell.Kind
        import CancelCell.Op

        def name: String                                  = "nio"
        def unavailable(using AllowUnsafe): Maybe[String] = Absent
        val supports: Set[Op]                             = Set(Op.Read, Op.Write, Op.Upgrade, Op.Connect)

        def open(kind: Kind)(using Frame, AllowUnsafe): CancelCell.Subject < (Async & Scope) =
            val driver = NioIoDriver.init()
            val loop   = driver.start()
            Scope.ensure(Sync.defer(driver.close())).andThen {
                val channels: (SocketChannel, Maybe[SocketChannel]) < (Async & Scope) = kind match
                    case Kind.Connected =>
                        Abort.run[IOException](NioLoopbackPair.open()).map(_.getOrThrow).map((client, peer) => (client, Present(peer)))
                    // A connect in flight to the black hole is never readable or writable either.
                    case Kind.Connecting | Kind.Stalled =>
                        Sync.defer {
                            val client = SocketChannel.open()
                            client.configureBlocking(false)
                            // RFC 5737 TEST-NET-1 answers neither SYN-ACK nor RST, so the connect stays in flight for the whole leaf.
                            discard(client.connect(new InetSocketAddress("192.0.2.1", 9)))
                            (client, Absent)
                        }
                    case Kind.Listening => throw new IllegalStateException("the NIO cell has no listening handle")
                channels.map { (client, peer) =>
                    val handle = NioHandle.init(client, 4096, Duration.Infinity, Duration.Infinity, Frame.internal)
                    assert(driver.registerChannel(handle), "registerChannel failed")
                    Scope.ensure(Sync.defer {
                        driver.release(handle, ReleaseMode.Graceful, Promise.Unsafe.init())
                        peer.foreach(_.close())
                    }).andThen(new CancelCell.DriverSubject[NioHandle](driver, handle):
                        def peerSend(bytes: Array[Byte])(using AllowUnsafe, Frame): Unit < Async =
                            Sync.defer(discard(peer.getOrElse(throw new IllegalStateException("no peer")).write(ByteBuffer.wrap(bytes))))
                        def closeListener()(using AllowUnsafe, Frame): Unit          = ()
                        def descriptorClosed(using AllowUnsafe, Frame): Unit < Async = Kyo.unit
                        def descriptorCloses(using AllowUnsafe): Int                 = 0
                        def stopDriver()(using AllowUnsafe, Frame): Unit < Async = Sync.defer(this.driver.close()).andThen(loop.safe.get))
                }
            }
        end open
    end Nio

end CancelContractCells
