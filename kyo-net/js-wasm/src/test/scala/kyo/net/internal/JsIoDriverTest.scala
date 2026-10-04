package kyo.net.internal

import kyo.*
import kyo.net.internal.transport.*
import kyo.scheduler.IOPromise
import scala.scalajs.js.annotation.JSImport
import scala.scalajs.js as sjs

/** Real-Node loopback tests for the [[JsIoDriver]] peer-close watch. Node gives no non-consuming FIN signal on a paused socket
  * (`pause()` calls `readStop`, so a FIN never reaches Node while parked), so the watch reads the socket on toward the stream end.
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

    /** A Node `stream.Duplex` standing in for a socket whose peer never drains it: `write` never calls back, so no write completes and
      * `end()` never emits `finish`; `read` never pushes, so no `data` or `end` arrives.
      */
    private def neverFlushingSocket(): sjs.Dynamic =
        sjs.Dynamic.newInstance(NodeStream.asInstanceOf[sjs.Dynamic].Duplex)(sjs.Dynamic.literal(
            write = ((_: sjs.Any, _: sjs.Any, _: sjs.Any) => ()): sjs.Function3[sjs.Any, sjs.Any, sjs.Any, Unit],
            read = ((_: sjs.Any) => ()): sjs.Function1[sjs.Any, Unit]
        ))

    private def buffer(bytes: Array[Byte]): sjs.Dynamic =
        sjs.Dynamic.global.Buffer.from(sjs.typedarray.byteArray2Int8Array(bytes).buffer)

    /** How many chunks a live peer sends while a watch is registered: the watch must stay pending across every one of them. */
    private val liveWatchChunks = 25

    /** Completed by the first of `events` on `socket`. Armed before the action that causes the event, since Node emits on a later turn. */
    private def nextEvent(socket: sjs.Dynamic, events: String*)(using Frame): Fiber[Unit, Any] =
        val promise                              = Promise.Unsafe.init[Unit, Any]()
        val settle: sjs.Function1[sjs.Any, Unit] = _ => promise.completeDiscard(Result.succeed(()))
        events.foreach(event => discard(socket.once(event, settle)))
        promise.safe
    end nextEvent

    private def watched(watch: Promise.Unsafe[Unit, Abort[Closed]])(using Frame): Result[Closed, Unit] < Async =
        Abort.run[Closed](watch.safe.get)

    "a watch registered while backpressured completes once the peer's FIN is read" in {
        given Frame = Frame.internal
        val driver  = JsIoDriver.init()
        openPair().map { case (serverSock, clientSock) =>
            val handle = JsHandle.init(serverSock, driver, Frame.internal)
            val watch  = Promise.Unsafe.init[Unit, Abort[Closed]]()
            // Backpressured: no awaitRead is armed. The peer writes 3 bytes then half-closes, which the paused socket cannot see on its own.
            discard(clientSock.write(buffer(Array[Byte](10, 20, 30))))
            discard(clientSock.end())
            driver.awaitPeerClose(handle, watch)
            watched(watch).map { outcome =>
                discard(clientSock.destroy())
                driver.closeHandle(handle)
                driver.close()
                assert(outcome == Result.succeed(()), s"the watch must report the peer's close, got $outcome")
                assert(handle.peerCloseWatch.isEmpty, "a completed watch must not stay registered")
            }
        }
    }

    "a watch on a live peer stays pending while it sends, and completes on its FIN" in {
        given Frame = Frame.internal
        val driver  = JsIoDriver.init()
        openPair().map { case (serverSock, clientSock) =>
            val handle = JsHandle.init(serverSock, driver, Frame.internal)
            val watch  = Promise.Unsafe.init[Unit, Abort[Closed]]()
            driver.awaitPeerClose(handle, watch)
            // Each chunk's 'data' event is awaited, so Node had the turn to surface a FIN, had there been one. A count of chunks, not a stretch
            // of wall-clock time.
            Loop(0) { i =>
                if i >= liveWatchChunks then Loop.done(true)
                else
                    val data = nextEvent(serverSock, "data")
                    discard(clientSock.write(buffer(Array[Byte](i.toByte))))
                    data.get.andThen(if watch.done() then Loop.done(false) else Loop.continue(i + 1))
                end if
            }.map { stayedPending =>
                // The watch read every chunk above, so it was live: the FIN now must complete it. Without this a watch that never resumed
                // the socket would stay pending just as happily, proving nothing.
                discard(clientSock.end())
                watched(watch).map { outcome =>
                    discard(clientSock.destroy())
                    driver.closeHandle(handle)
                    driver.close()
                    assert(stayedPending, "the watch must stay pending for a live peer that has not sent a FIN")
                    assert(outcome == Result.succeed(()), s"the watch must report the peer's FIN, got $outcome")
                }
            }
        }
    }

    "a withdrawn watch is not completed by the peer's FIN, while the watch that replaced it is" in {
        given Frame = Frame.internal
        val driver  = JsIoDriver.init()
        openPair().map { case (serverSock, clientSock) =>
            val handle    = JsHandle.init(serverSock, driver, Frame.internal)
            val withdrawn = Promise.Unsafe.init[Unit, Abort[Closed]]()
            val current   = Promise.Unsafe.init[Unit, Abort[Closed]]()
            driver.awaitPeerClose(handle, withdrawn)
            driver.cancelPeerCloseWatch(handle, withdrawn)
            discard(clientSock.end())
            driver.awaitPeerClose(handle, current)
            watched(current).map { outcome =>
                discard(clientSock.destroy())
                driver.closeHandle(handle)
                driver.close()
                assert(outcome == Result.succeed(()), s"the registered watch must report the peer's FIN, got $outcome")
                assert(!withdrawn.done(), "a withdrawn watch must never be completed")
            }
        }
    }

    "the diagnostics probe reports an armed read as pending until its bytes arrive, and close() unregisters it" in {
        given Frame = Frame.internal
        val driver  = JsIoDriver.init()
        discard(driver.start())
        val name                                           = "JsIoDriver@" + java.lang.System.identityHashCode(driver)
        def probe(): Maybe[kyo.internal.Diagnostics.Probe] =
            Maybe.fromOption(kyo.internal.Diagnostics.probeAll().collectFirst { case (n, p) if n.startsWith(name) => p })
        openPair().map { case (serverSock, clientSock) =>
            val handle = JsHandle.init(serverSock, driver, Frame.internal)
            val read   = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
            driver.awaitRead(handle, read)
            val armed     = probe()
            val armedDump = kyo.internal.Diagnostics.dumpAll()
            discard(clientSock.write(buffer(Array[Byte](7))))
            read.safe.get.map { outcome =>
                val delivered = probe()
                discard(clientSock.destroy())
                driver.closeHandle(handle)
                driver.close()
                val gotByte = outcome match
                    case ReadOutcome.Bytes(s) => s.toArray.toList == List[Byte](7)
                    case _                    => false
                assert(gotByte, s"expected the written byte, got $outcome")
                assert(
                    armed == Present(kyo.internal.Diagnostics.Probe(closed = false, cycles = 1L, pending = true)),
                    s"an armed read must report pending with one armed op, got $armed"
                )
                assert(
                    armedDump.contains(s"pendingReads=[${driver.handleLabel(handle)} ]"),
                    s"the dump must name the armed handle: $armedDump"
                )
                assert(
                    delivered == Present(kyo.internal.Diagnostics.Probe(closed = false, cycles = 1L, pending = false)),
                    s"a delivered read must no longer report pending, got $delivered"
                )
                assert(probe() == Absent, "close() must remove the driver's diagnostics registration")
            }
        }
    }

    "a graceful close whose output never flushes destroys the socket at closeFlushGrace on the handle's clock, not one tick before" in {
        given Frame = Frame.internal
        Clock.withTimeControl { tc =>
            Clock.get.map { clock =>
                val driver = JsIoDriver.init()
                // Not a real socket: how much of a write the kernel accepts from a peer that never reads is OS-dependent (Windows AFD takes
                // a whole 64 MiB overlapped send), so a real socket can emit `finish` and settle the close before the grace. The output is a
                // Duplex whose write callback never runs, so `end()` can never emit `finish` and the grace timer is the close's only exit.
                val socket = neverFlushingSocket()
                val handle = JsHandle.init(socket, driver, Frame.internal)
                handle.closeFlushGrace = 30.seconds
                handle.clock = clock
                discard(socket.write(buffer(Array[Byte](1))))
                driver.closeHandle(handle)
                def destroyed = socket.destroyed.asInstanceOf[Boolean]
                val atClose   = destroyed
                tc.advance((30.seconds.toMillis - 1).millis).map { _ =>
                    val oneTickBefore = destroyed
                    val flushed       = socket.writableFinished.asInstanceOf[Boolean]
                    tc.advance(1.millis).map { _ =>
                        val atGrace = destroyed
                        driver.close()
                        assert(!atClose, "the graceful close must not destroy a socket whose output is still flushing")
                        assert(!flushed, "the write never completed, so the output must not have finished; the leaf would test nothing")
                        assert(!oneTickBefore, "the socket must survive until the grace ends")
                        assert(atGrace, "the socket must be destroyed when the grace ends")
                    }
                }
            }
        }
    }

    "an abandoned backpressured connection is reclaimed at the grace after the peer FIN, on the transport's clock" in {
        given Frame = Frame.internal
        val grace   = 200.millis
        // Small inbound channel so two chunks overflow it.
        val config = kyo.net.NetConfig(channelCapacity = 1, readChunkSize = 64, peerCloseGrace = grace)
        // Capture the accepted (server) connection: with its ReadPump parked on the full cap-1 channel the client FIN is observable only through
        // the peer-close watch, so the captured connection's close is the reclaim oracle, validating that the transport threads the grace and its
        // clock. The client is a raw Node socket, so the accepted side's grace is the only sleep on the controlled clock.
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
                    // fills the cap-1 channel, the other overflows it and parks the pump. Once the second has surfaced no read is armed, so the
                    // FIN below can reach the pump only through its watch.
                    firstData = nextEvent(socket, "data")
                    _ <- Sync.defer(discard(client.write(buffer(Array.fill[Byte](64)(1)))))
                    _ <- firstData.get
                    secondData = nextEvent(socket, "data")
                    _ <- Sync.defer(discard(client.write(buffer(Array.fill[Byte](64)(2)))))
                    _ <- secondData.get
                    _ <- Sync.defer(discard(client.end()))
                    // The grace sleep is armed only once the watch reports the FIN, so the pending sleeper IS the observed close.
                    _ <- tc.awaitPendingSleepers(1)
                    _ = assert(
                        accepted.inbound.pendingPuts().getOrElse(0) == 1,
                        "the accepted-side ReadPump must be parked on the full channel"
                    )
                    closing = accepted.onClosing.safe
                    _ <- tc.advance((grace.toMillis - 1).millis)
                    openOneTickBefore = accepted.isOpen
                    _ <- tc.advance(1.millis)
                    _ <- closing.get
                yield
                    discard(client.destroy())
                    assert(openOneTickBefore, "the connection must stay open until the grace after the FIN ends")
                    assert(!accepted.isOpen, "the grace must reclaim the abandoned connection whose peer has closed")
                end for
            }
        }
    }

end JsIoDriverTest

// Imported like the facades in NodeBuiltins, since the Wasm backend links this suite as an ES module without `require`.
@sjs.native
@JSImport("node:stream", JSImport.Namespace)
private object NodeStream extends sjs.Object
