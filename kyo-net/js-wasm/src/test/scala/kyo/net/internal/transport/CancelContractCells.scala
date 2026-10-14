package kyo.net.internal.transport

import kyo.*
import kyo.net.internal.JsHandle
import kyo.net.internal.JsIoDriver
import kyo.net.internal.NodeNet
import kyo.scheduler.IOPromise
import scala.scalajs.js as sjs

/** The JS and Wasm [[CancelCell]]: the Node driver over a real loopback pair. Node completes `awaitConnect` from its own `connect` event and
  * has no `awaitAccept`, and its sockets carry no descriptor to count.
  */
object CancelContractCells:

    val all: Seq[CancelCell] = Seq(Node)

    object Node extends CancelCell:
        import CancelCell.Kind
        import CancelCell.Op

        def name: String                                  = "node"
        def unavailable(using AllowUnsafe): Maybe[String] = Absent
        val supports: Set[Op]                             = Set(Op.Read, Op.Write, Op.Upgrade)

        private def net: sjs.Dynamic = NodeNet.asInstanceOf[sjs.Dynamic]

        /** A connected pair: the accepted socket, paused for a handle, and the client as its peer. */
        private def openPair()(using Frame, AllowUnsafe): (sjs.Dynamic, sjs.Dynamic) < Async =
            val p = new IOPromise[Nothing, (sjs.Dynamic, sjs.Dynamic)]
            Sync.defer {
                val server              = net.createServer()
                var client: sjs.Dynamic = null
                discard(server.on(
                    "connection",
                    { (socket: sjs.Dynamic) =>
                        discard(socket.pause())
                        discard(server.close())
                        p.completeDiscard(Result.succeed((socket, client)))
                    }: sjs.Function1[sjs.Dynamic, Unit]
                ))
                discard(server.listen(
                    0,
                    "127.0.0.1",
                    { () => client = net.connect(server.address().port, "127.0.0.1") }: sjs.Function0[Unit]
                ))
            }.andThen(p.asInstanceOf[Fiber.Unsafe[(sjs.Dynamic, sjs.Dynamic), Any]].safe.get)
        end openPair

        def open(kind: Kind)(using Frame, AllowUnsafe): CancelCell.Subject < (Async & Scope) =
            // A connected pair is also the stalled handle here: a Node writable wait completes only on a `drain` event, and no event-loop
            // turn can run between the leaf's synchronous arm and the call under test.
            if kind != Kind.Connected && kind != Kind.Stalled then throw new IllegalStateException(s"the node cell has no $kind handle")
            val driver = JsIoDriver.init()
            val loop   = driver.start()
            Scope.ensure(Sync.defer(driver.close())).andThen {
                openPair().map { (socket, peer) =>
                    val handle = JsHandle.init(socket, driver, Frame.internal)
                    Scope.ensure(Sync.defer {
                        driver.closeHandle(handle)
                        discard(peer.destroy())
                    }).andThen(new CancelCell.DriverSubject[JsHandle](driver, handle):
                        def peerSend(bytes: Array[Byte])(using AllowUnsafe, Frame): Unit < Async =
                            Sync.defer(
                                discard(peer.write(sjs.Dynamic.global.Buffer.from(sjs.typedarray.byteArray2Int8Array(bytes).buffer)))
                            )
                        def closeListener()(using AllowUnsafe, Frame): Unit          = ()
                        def descriptorClosed(using AllowUnsafe, Frame): Unit < Async = Kyo.unit
                        def descriptorCloses(using AllowUnsafe): Int                 = 0
                        def stopDriver()(using AllowUnsafe, Frame): Unit < Async = Sync.defer(this.driver.close()).andThen(loop.safe.get))
                }
            }
        end open
    end Node

end CancelContractCells
