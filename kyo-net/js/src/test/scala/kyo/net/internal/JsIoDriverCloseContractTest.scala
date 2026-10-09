package kyo.net.internal

import kyo.*
import kyo.net.NetException
import kyo.net.internal.transport.ReadOutcome
import kyo.scheduler.IOPromise
import scala.scalajs.js as sjs

/** Close-contract leaves for [[JsIoDriver]] that the public surface cannot isolate: what `cancel` does to the promises parked on a handle.
  * Each leaf runs over a real Node loopback pair; the driver calls under test are synchronous on the event loop, so every assertion reads
  * the promise right after the call that must have settled it.
  */
class JsIoDriverCloseContractTest extends kyo.net.Test:

    import AllowUnsafe.embrace.danger

    private def net: sjs.Dynamic = NodeNet.asInstanceOf[sjs.Dynamic]

    /** A connected loopback pair: (accepted server socket, paused; client socket). */
    private def openPair()(using Frame): (sjs.Dynamic, sjs.Dynamic) < (Async & Abort[Closed]) =
        val p = new IOPromise[Closed, (sjs.Dynamic, sjs.Dynamic)]
        Sync.defer {
            val server              = net.createServer()
            var client: sjs.Dynamic = null
            discard(server.on(
                "connection",
                { (sock: sjs.Dynamic) =>
                    discard(sock.pause())
                    discard(server.close())
                    p.completeDiscard(Result.succeed((sock, client)))
                }: sjs.Function1[sjs.Dynamic, Unit]
            ))
            discard(server.listen(
                0,
                "127.0.0.1",
                { () =>
                    client = net.connect(server.address().port, "127.0.0.1")
                }: sjs.Function0[Unit]
            ))
        }.andThen(p.asInstanceOf[Fiber.Unsafe[(sjs.Dynamic, sjs.Dynamic), Abort[Closed]]].safe.get)
    end openPair

    "cancel fails both a parked read and a parked writable with Closed".pendingUntilFixed(
        "N3: cancel fails only the read; a parked writable survives release"
    ) in {
        given Frame = Frame.internal
        val driver  = JsIoDriver.init()
        openPair().map { case (serverSock, clientSock) =>
            val handle   = JsHandle.init(serverSock, driver, Frame.internal)
            val read     = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
            val writable = Promise.Unsafe.init[Unit, Abort[Closed | NetException]]()
            driver.awaitRead(handle, read)
            driver.awaitWritable(handle, writable)
            driver.cancel(handle)
            val readOutcome     = read.poll()
            val writableOutcome = writable.poll()
            discard(clientSock.destroy())
            driver.closeHandle(handle)
            driver.close()
            assert(failedClosed(readOutcome), s"cancel must fail the parked read with Closed, got $readOutcome")
            assert(failedClosed(writableOutcome), s"cancel must fail the parked writable with Closed, got $writableOutcome")
        }
    }

    private def failedClosed[E, A](outcome: Maybe[Result[E, A]]): Boolean =
        outcome match
            case Present(Result.Failure(_: Closed)) => true
            case _                                  => false

end JsIoDriverCloseContractTest
