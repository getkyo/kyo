package kyo.net.internal

import java.io.IOException
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import kyo.*

/** A connected TCP loopback pair of NIO channels for driver tests: `(client, accepted)`, the client non-blocking (ready to register with a
  * driver) and the accepted side in the JDK's default blocking mode. The caller closes both; the listener is always closed here.
  *
  * The accept waits for the client's connect to finish, never the other way round. A non-blocking connect can fail after it returns: on
  * Windows, once a suite has cycled the ephemeral range, a new loopback connect whose 4-tuple matches one still in TIME_WAIT fails with
  * WSAEADDRINUSE, reported only by `finishConnect`, and the listener never sees it. A setup that waits on the accept first then waits forever;
  * this one fails with the connect's own exception. Both waits end on an event that must happen (the connect resolves either way, and an
  * established connection is in the backlog), so neither carries a deadline, and neither blocks a carrier.
  */
object NioLoopbackPair:

    /** A pair whose ends are configured before the bind (`listener`) and before the connect (`client`), for socket options that have to be
      * in place by then.
      */
    def open(
        configureListener: ServerSocketChannel => Unit = _ => (),
        configureClient: SocketChannel => Unit = _ => ()
    )(using Frame): (SocketChannel, SocketChannel) < (Async & Abort[IOException]) =
        Sync.defer(Abort.catching[IOException] {
            val listener = ServerSocketChannel.open()
            try
                configureListener(listener)
                listener.configureBlocking(false)
                listener.bind(new InetSocketAddress("127.0.0.1", 0))
                listener
            catch
                case e: Throwable =>
                    listener.close()
                    throw e
            end try
        }).map { listener =>
            Sync.ensure(Sync.defer(listener.close())) {
                connectTo(listener, listener.getLocalAddress, configureClient)
            }
        }
    end open

    /** Connects a client to `target` and accepts it on `listener`. Separate from [[open]] so a test can aim the connect where it fails. */
    private[internal] def connectTo(
        listener: ServerSocketChannel,
        target: SocketAddress,
        configureClient: SocketChannel => Unit = _ => ()
    )(using Frame): (SocketChannel, SocketChannel) < (Async & Abort[IOException]) =
        Sync.defer(SocketChannel.open()).map { client =>
            Abort.run[IOException] {
                Sync.defer(Abort.catching[IOException] {
                    configureClient(client)
                    client.configureBlocking(false)
                    discard(client.connect(target))
                }).andThen(connected(client)).andThen(accepted(listener)).map(server => (client, server))
            }.map {
                case Result.Success(pair) => pair
                case failed               => Sync.defer(client.close()).andThen(Abort.get(failed))
            }
        }
    end connectTo

    private def connected(client: SocketChannel)(using Frame): Unit < (Async & Abort[IOException]) =
        Loop.foreach {
            Sync.defer(Abort.catching[IOException](client.finishConnect())).map { done =>
                if done then Loop.done(())
                else Async.sleep(1.millis).andThen(Loop.continue)
            }
        }

    private def accepted(listener: ServerSocketChannel)(using Frame): SocketChannel < (Async & Abort[IOException]) =
        Loop(()) { _ =>
            Sync.defer(Abort.catching[IOException](listener.accept())).map { server =>
                if server ne null then Loop.done(server)
                else Async.sleep(1.millis).andThen(Loop.continue(()))
            }
        }

end NioLoopbackPair
