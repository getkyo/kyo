package kyo.net.internal

import kyo.*
import kyo.net.internal.transport.*
import kyo.scheduler.IOPromise
import scala.scalajs.js as sjs

/** Real-Node loopback tests for the [[JsIoDriver]] peer-close grace probe. Node gives no non-consuming FIN signal on a paused socket
  * (`pause()` calls `readStop`, so a FIN never reaches Node while parked), so `isPeerClosed` detects the FIN by draining the socket toward
  * the stream end.
  */
class JsIoDriverTest extends kyo.net.Test:

    import AllowUnsafe.embrace.danger

    private def net: sjs.Dynamic = sjs.Dynamic.global.require("net")

    /** Open a connected loopback pair on Node. Returns (serverSocket, clientSocket); the server socket is PAUSED and wrapped in nothing yet. */
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
                    val port = server.address().port
                    client = net.connect(port, "127.0.0.1")
                }: sjs.Function0[Unit]
            ))
        }.andThen(p.asInstanceOf[Fiber.Unsafe[(sjs.Dynamic, sjs.Dynamic), Abort[Closed]]].safe.get)
    end openPair

    private def buffer(bytes: Array[Byte]): sjs.Dynamic =
        sjs.Dynamic.global.Buffer.from(sjs.typedarray.byteArray2Int8Array(bytes).buffer)

    /** Poll `cond` on the fiber scheduler (never a thread block) until it holds or `bound` elapses; returns whether it held. */
    private def awaitCondition(bound: Duration)(cond: => Boolean)(using Frame): Boolean < Async =
        val deadline = java.lang.System.nanoTime() + bound.toNanos
        Loop(()) { _ =>
            if cond then Loop.done(true)
            else if java.lang.System.nanoTime() >= deadline then Loop.done(false)
            else Async.sleep(5.millis).andThen(Loop.continue(()))
        }
    end awaitCondition

    "isPeerClosed observes a peer FIN while backpressured by resuming for one chunk at a time" in {
        given Frame = Frame.internal
        val driver  = JsIoDriver.init()
        openPair().map { case (serverSock, clientSock) =>
            val handle = JsHandle.init(serverSock, driver)
            // Backpressured: no awaitRead is armed. The peer writes 3 bytes then half-closes (FIN via end()).
            discard(clientSock.write(buffer(Array[Byte](10, 20, 30))))
            discard(clientSock.end())
            // each call resumes one chunk toward the FIN; poll until the latch is observed.
            assert(!driver.isPeerClosed(handle), "first isPeerClosed resumes the probe and returns false (not observed yet)")
            awaitCondition(5.seconds)(driver.isPeerClosed(handle)).map { observed =>
                discard(clientSock.destroy())
                driver.closeHandle(handle)
                driver.close()
                assert(observed, "isPeerClosed must observe the peer FIN after resuming the paused socket drains to the stream end")
                succeed
            }
        }
    }

    "isPeerClosed stays false for a live peer that has not closed" in {
        given Frame = Frame.internal
        val driver  = JsIoDriver.init()
        openPair().map { case (serverSock, clientSock) =>
            val handle = JsHandle.init(serverSock, driver)
            // The peer stays connected and sends nothing: resuming yields no data and no 'end', so readableEnded/destroyed stay false.
            assert(!driver.isPeerClosed(handle), "first isPeerClosed returns false")
            Async.sleep(300.millis).map { _ =>
                val stillOpen = !driver.isPeerClosed(handle)
                discard(clientSock.destroy())
                driver.closeHandle(handle)
                driver.close()
                assert(stillOpen, "isPeerClosed must stay false for a live peer that has not sent a FIN")
                succeed
            }
        }
    }

    "an abandoned backpressured connection is reclaimed after the peer FIN within the grace window" in {
        given Frame   = Frame.internal
        val transport = kyo.net.NetPlatform.transport
        // Small inbound channel so two chunks overflow it. Short grace so the reclaim completes well inside the leaf's poll budget.
        val config = kyo.net.NetConfig(channelCapacity = 1, readChunkSize = 64, peerCloseGrace = 200.millis)
        // Capture the accepted (server) connection: with its ReadPump parked on the full cap-1 channel the client FIN is observable only through the
        // peer-close grace poll, so the captured connection's isOpen is the portable reclaim oracle, validating that the transport threads the grace.
        val acceptedP = new IOPromise[Closed, kyo.net.Connection]
        for
            listener <- transport.listen("127.0.0.1", 0, 128, config) { conn =>
                acceptedP.completeDiscard(Result.succeed(conn))
            }.safe.get
            _        <- Scope.ensure(Sync.defer(listener.close()))
            client   <- transport.connect("127.0.0.1", listener.port, config = config).safe.get
            accepted <- acceptedP.asInstanceOf[Fiber.Unsafe[kyo.net.Connection, Abort[Closed]]].safe.get
            // Two writes with a gap so Node emits two 'data' events: one fills the cap-1 inbound, the other overflows and parks the pump.
            _         <- Abort.run[Closed](client.outbound.safe.put(Span.fromUnsafe(Array.fill[Byte](64)(1))))
            _         <- Async.sleep(150.millis)
            _         <- Abort.run[Closed](client.outbound.safe.put(Span.fromUnsafe(Array.fill[Byte](64)(2))))
            _         <- Async.sleep(150.millis)    // let the accepted-side ReadPump read both chunks and park on the put
            _         <- Sync.defer(client.close()) // FIN with the accepted-side pump parked
            reclaimed <- awaitCondition(5.seconds)(!accepted.isOpen)
        yield assert(
            reclaimed,
            "the abandoned backpressured accepted connection must be reclaimed after the client FIN via the peer-close grace"
        )
        end for
    }

end JsIoDriverTest
