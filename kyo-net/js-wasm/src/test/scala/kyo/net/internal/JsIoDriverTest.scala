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

    // `NodeNet` rather than `sjs.Dynamic.global.require("net")`: this suite is shared with the Wasm backend, which links as an ES
    // module, where `require` is not defined.
    private def net: sjs.Dynamic = NodeNet.asInstanceOf[sjs.Dynamic]

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

    /** How many times a standing probe is read while its peer is open: each read resumes it once, so a latch on any is the regression.
      * The "stays false" leaf asserts over this read count, not a clock.
      */
    private val liveWatchReads = 25

    /** Completed by the first of `events` on `socket`. Armed before the action that causes the event, since Node emits on a later turn. */
    private def nextEvent(socket: sjs.Dynamic, events: String*)(using Frame): Fiber[Unit, Any] =
        val promise                              = Promise.Unsafe.init[Unit, Any]()
        val settle: sjs.Function1[sjs.Any, Unit] = _ => promise.completeDiscard(Result.succeed(()))
        events.foreach(event => discard(socket.once(event, settle)))
        promise.safe
    end nextEvent

    /** Reads the probe until it reports the peer closed. Each read that reports false resumed the socket, so the next `data`, `end` or
      * `close` is awaited before reading again: the wait is bounded by Node's events, never by a clock.
      */
    private def untilPeerClosed(driver: JsIoDriver, handle: JsHandle)(using Frame): Unit < Async =
        Loop.foreach {
            Sync.defer {
                val next = nextEvent(handle.socket, "data", "end", "close")
                if driver.isPeerClosed(handle) then Loop.done(()) else next.get.andThen(Loop.continue)
            }
        }

    "isPeerClosed observes a peer FIN while backpressured by resuming for one chunk at a time" in {
        given Frame = Frame.internal
        val driver  = JsIoDriver.init()
        openPair().map { case (serverSock, clientSock) =>
            val handle = JsHandle.init(serverSock, driver, Frame.internal)
            // Backpressured: no awaitRead is armed. The peer writes 3 bytes then half-closes (FIN via end()).
            discard(clientSock.write(buffer(Array[Byte](10, 20, 30))))
            discard(clientSock.end())
            // Each call resumes one chunk toward the FIN.
            assert(!driver.isPeerClosed(handle), "first isPeerClosed resumes the probe and returns false (not observed yet)")
            untilPeerClosed(driver, handle).map { _ =>
                val observed = driver.isPeerClosed(handle)
                discard(clientSock.destroy())
                driver.closeHandle(handle)
                driver.close()
                assert(observed, "isPeerClosed must observe the peer FIN after resuming the paused socket drains to the stream end")
            }
        }
    }

    "isPeerClosed stays false for a live peer that has not closed" in {
        given Frame = Frame.internal
        val driver  = JsIoDriver.init()
        openPair().map { case (serverSock, clientSock) =>
            val handle = JsHandle.init(serverSock, driver, Frame.internal)
            // The peer stays connected and sends one byte per read, never a FIN: each read resumes the socket, the byte's 'data' event is awaited
            // (so Node had the turn to surface a FIN, had there been one), and every read must report false. A count of reads, not a stretch of
            // wall-clock time.
            assert(!driver.isPeerClosed(handle), "first isPeerClosed returns false")
            Loop(0) { i =>
                if i >= liveWatchReads then Loop.done(true)
                else
                    val data = nextEvent(serverSock, "data")
                    discard(clientSock.write(buffer(Array[Byte](i.toByte))))
                    if driver.isPeerClosed(handle) then Loop.done(false)
                    else data.get.andThen(Loop.continue(i + 1))
                end if
            }.map { stayedOpen =>
                assert(stayedOpen, "isPeerClosed must stay false for a live peer that has not sent a FIN")
            }.andThen {
                // Prove the probe was live, not dead: half-closing the peer now must make it observe the FIN. Without this a probe that never
                // resumed the socket would report false just as happily, proving nothing.
                discard(clientSock.end())
                untilPeerClosed(driver, handle).map { _ =>
                    val observed = driver.isPeerClosed(handle)
                    discard(clientSock.destroy())
                    driver.closeHandle(handle)
                    driver.close()
                    assert(observed, "the probe was not live: the peer FIN was never observed after the reads above")
                }
            }
        }
    }

    "a graceful close whose output never flushes destroys the socket at peerCloseGrace on the handle's clock, not one tick before" in {
        given Frame = Frame.internal
        Clock.withTimeControl { tc =>
            Clock.get.map { clock =>
                val driver = JsIoDriver.init()
                openPair().map { case (serverSock, clientSock) =>
                    // A peer that never reads: 64 MiB is past what the loopback socket buffers hold, so Node never emits `finish`.
                    discard(clientSock.pause())
                    val handle = JsHandle.init(serverSock, driver, Frame.internal)
                    handle.peerCloseGrace = 30.seconds
                    handle.clock = clock
                    discard(serverSock.write(buffer(new Array[Byte](64 * 1024 * 1024))))
                    driver.closeHandle(handle)
                    def destroyed = serverSock.destroyed.asInstanceOf[Boolean]
                    val atClose   = destroyed
                    tc.advance((30.seconds.toMillis - 1).millis).map { _ =>
                        val oneTickBefore = destroyed
                        val flushed       = serverSock.writableFinished.asInstanceOf[Boolean]
                        tc.advance(1.millis).map { _ =>
                            val atGrace = destroyed
                            discard(clientSock.destroy())
                            driver.close()
                            assert(!atClose, "the graceful close must not destroy a socket whose output is still flushing")
                            assert(!flushed, "the peer never read, so the output must not have finished; the leaf would test nothing")
                            assert(!oneTickBefore, "the socket must survive until the grace ends")
                            assert(atGrace, "the socket must be destroyed when the grace ends")
                        }
                    }
                }
            }
        }
    }

    "an abandoned backpressured connection is reclaimed after the peer FIN at the grace on the transport's clock" in {
        given Frame = Frame.internal
        val grace   = 200.millis
        // Small inbound channel so two chunks overflow it.
        val config = kyo.net.NetConfig(channelCapacity = 1, readChunkSize = 64, peerCloseGrace = grace)
        // Capture the accepted (server) connection: with its ReadPump parked on the full cap-1 channel the client FIN is observable only through the
        // peer-close grace poll, so the captured connection's close is the reclaim oracle, validating that the transport threads the grace and
        // its clock. The client is a raw Node socket, so the accepted side's grace is the only sleep on the controlled clock.
        Clock.withTimeControl { tc =>
            Clock.get.map { clock =>
                val transport = JsTransport.init(poolSize = 1, clock = clock)
                val acceptedP = new IOPromise[Closed, kyo.net.Connection]
                for
                    listener <- transport.listen("127.0.0.1", 0, 128, config) { conn =>
                        acceptedP.completeDiscard(Result.succeed(conn))
                    }.safe.get
                    _        <- Scope.ensure(Sync.defer(listener.close()))
                    client   <- Sync.defer(net.connect(listener.port, "127.0.0.1"))
                    accepted <- acceptedP.asInstanceOf[Fiber.Unsafe[kyo.net.Connection, Abort[Closed]]].safe.get
                    socket = accepted.asInstanceOf[Connection[JsHandle]].handle.socket
                    // Two writes, the second sent only once Node surfaced the first on the accepted socket, so they are two 'data' events: one
                    // fills the cap-1 channel, the other overflows it and parks the pump.
                    firstData = nextEvent(socket, "data")
                    _ <- Sync.defer(discard(client.write(buffer(Array.fill[Byte](64)(1)))))
                    _ <- firstData.get
                    _ <- Sync.defer(discard(client.write(buffer(Array.fill[Byte](64)(2)))))
                    // The pump arms its grace on the controlled clock exactly when it parks, so the pending sleeper IS the parked state. A FIN
                    // arriving while a read is still armed takes the ordinary EOF path, leaving the grace reclaim untested.
                    _ <- tc.awaitPendingSleepers(1)
                    _ = assert(
                        accepted.inbound.pendingPuts().getOrElse(0) == 1,
                        "the accepted-side ReadPump must be parked on the full channel"
                    )
                    end = nextEvent(socket, "end")
                    _ <- Sync.defer(discard(client.end())) // FIN with the accepted-side pump parked
                    // The first expiry finds the paused socket not yet ended: the probe resumes it toward the FIN and the grace re-arms.
                    _ <- tc.advance(grace)
                    _ <- end.get
                    _ <- tc.awaitPendingSleepers(1)
                    openAfterFirstExpiry = accepted.isOpen
                    closing              = accepted.onClosing.safe
                    _ <- tc.advance(grace)
                    _ <- closing.get
                yield
                    discard(client.destroy())
                    assert(openAfterFirstExpiry, "the first expiry cannot have observed the FIN on a paused socket, so it must not reclaim")
                    assert(!accepted.isOpen, "the second expiry must reclaim the abandoned connection whose peer has closed")
                end for
            }
        }
    }

end JsIoDriverTest
