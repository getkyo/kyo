package kyo.net.internal

import kyo.*
import kyo.net.internal.transport.*
import kyo.net.internal.util.HandleId
import kyo.scheduler.IOPromise
import scala.scalajs.js as sjs

/** Reproduce-first regression for a STARTTLS upgrade-handoff drop on the JS Node driver: without an `onInboundClosedDuringRead` override,
  * [[JsIoDriver]] falls back to [[kyo.net.internal.transport.IoDriver]]'s no-op default, so a STARTTLS upgrade racing the plaintext
  * [[kyo.net.internal.transport.ReadPump]]'s parked put silently drops bytes already pulled off the socket instead of salvaging them into the
  * handle's leftover queue that [[JsTransport.upgradeToTls]]'s afterDetach drains and unshifts into the handshake. It was the one STARTTLS-capable
  * backend missing this override, which stranded one of many concurrent upgrades at the handshake deadline under load (only the JS CI job saw it;
  * NIO/io_uring/poller already salvage). The JS arm provides the matching override, staging into leftover.
  *
  * The main scenario drives the race directly rather than through a real TLS handshake ([[kyo.net.TransportStartTlsConcurrentTest]] exercises that
  * end to end): with `channelCapacity=1` and nothing consuming `conn.inbound`, chunk A fills the channel; chunk B's delivery then parks the pump's
  * putFiber (`Channel.offer` returns false, `ReadPump.offerToChannel` falls to the putFiber branch). Setting `upgrading=true` and calling
  * `detachForUpgrade()` closes `inbound`, which both returns `[A]` (the already-buffered chunk) and fails B's parked put with `Closed`, invoking
  * `driver.onInboundClosedDuringRead`. Without the override this drops B; with it, B lands in the handle's leftover queue. The other two scenarios
  * pin the hook's contract directly: staging on an upgrade close, and discarding on an ordinary (non-upgrade) close.
  *
  * Anti-flakiness: waits for `conn.inbound.size() == 1` (chunk A landed) and the pump re-armed for B (`pendingRead` set), then for B's delivery to
  * clear that read and park the put (`pendingRead` empty again) before detaching, then polls the parked put's async salvage. No sleep-as-assertion.
  * The client fd, handle, and driver are released on every path so a failed assertion (a real regression) surfaces as that assertion, not a leak.
  */
class JsIoDriverUpgradeHandoffDropTest extends kyo.net.Test:

    import AllowUnsafe.embrace.danger
    given Frame = Frame.internal

    // `NodeNet` rather than `sjs.Dynamic.global.require("net")`: this suite is shared with the Wasm backend, which links as an ES module, where
    // `require` is not defined.
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

    /** An inert stand-in for a socket the leftover-only scenarios never drive: `onInboundClosedDuringRead` touches `upgrading` and the leftover
      * queue, never the socket. A bare literal (not a `require`d EventEmitter) keeps these scenarios runnable on the Wasm backend too, which links
      * as an ES module where `require` is undefined.
      */
    private def inertSocket(): sjs.Dynamic = sjs.Dynamic.literal()

    /** Poll `cond` on the fiber scheduler (never a thread block) until it holds or `bound` elapses; returns whether it held. */
    private def awaitCondition(bound: Duration)(cond: => Boolean)(using Frame): Boolean < Async =
        val deadline = java.lang.System.nanoTime() + bound.toNanos
        Loop(()) { _ =>
            if cond then Loop.done(true)
            else if java.lang.System.nanoTime() >= deadline then Loop.done(false)
            else Async.sleep(2.millis).andThen(Loop.continue(()))
        }
    end awaitCondition

    "JsIoDriver STARTTLS upgrade-handoff" - {

        "salvages a plaintext ReadPump chunk parked on a full inbound channel when detachForUpgrade races it" in {
            val driver = JsIoDriver.init()
            openPair().map { case (serverSock, clientSock) =>
                val handle = JsHandle.init(serverSock, driver, Frame.internal)
                val conn   = Connection.init(handle, driver, channelCapacity = 1)
                conn.start()

                val chunkA = Array[Byte](1, 2, 3, 4, 5)
                val chunkB = Array[Byte](9, 8, 7)

                discard(clientSock.write(buffer(chunkA)))

                // Chunk A fills the capacity-1 channel and the pump re-arms for the next read (pendingRead set): both hold before B is sent.
                awaitCondition(5.seconds)(conn.inbound.size().getOrElse(-1) == 1 && handle.pendingRead.isDefined).map { armed =>
                    assert(armed, "chunk A never landed and the pump never re-armed (a hang, not the race under test)")

                    discard(clientSock.write(buffer(chunkB)))

                    // Chunk B's 'data' clears the pending read and offerToChannel parks its put (channel full, A unconsumed); the pump does NOT
                    // re-arm while parked, so pendingRead stays empty.
                    awaitCondition(5.seconds)(handle.pendingRead.isEmpty).map { parked =>
                        assert(parked, "chunk B was never delivered / its put never parked (a hang, not the race under test)")

                        handle.upgrading = true
                        val buffered = conn.detachForUpgrade().poll() match
                            case Present(Result.Success(v)) => v.eval
                            case other                      => fail(s"detachForUpgrade did not settle synchronously: $other")
                        val bufferedBytes: Array[Byte] =
                            buffered.map(chunks => chunks.toArray.flatMap(_.toArray)).getOrElse(Array.emptyByteArray)
                        assert(
                            bufferedBytes.toSeq == chunkA.toSeq,
                            s"detachForUpgrade must return the already-buffered chunk A, got ${bufferedBytes.toSeq}"
                        )

                        // The core regression guard: chunk B, off the socket and parked in the pump's put when detachForUpgrade raced it, must be
                        // salvaged into the leftover queue instead of dropped. The parked put's onComplete (which invokes onInboundClosedDuringRead)
                        // is a raw IOPromise callback that runs synchronously inside inbound.close() on this single-threaded platform, so the
                        // leftover is already staged by the time this runs; the poll is a harmless guard, not a wait for a rescheduled callback.
                        awaitCondition(5.seconds)(handle.hasLeftover).map { salvaged =>
                            val leftover = handle.dequeueLeftover() match
                                case Present(JsHandle.Leftover(buf, off, len)) => java.util.Arrays.copyOfRange(buf, off, off + len)
                                case Absent                                    => Array.emptyByteArray
                            discard(clientSock.destroy())
                            driver.closeHandle(handle)
                            driver.close()
                            assert(salvaged, "chunk B was dropped instead of salvaged into the handle's leftover queue")
                            assert(
                                leftover.toSeq == chunkB.toSeq,
                                s"salvaged leftover bytes ${leftover.toSeq} did not match chunk B ${chunkB.toSeq}"
                            )
                            succeed
                        }
                    }
                }
            }
        }

        "stages the peer's first flight as leftover when the handle is upgrading (afterDetach replays it)" in {
            val driver = JsIoDriver.init()
            val handle = new JsHandle(inertSocket(), HandleId.next(0), Frame.internal)
            handle.upgrading = true
            val bytes = Array[Byte](11, 22, 33)
            driver.onInboundClosedDuringRead(handle, Span.fromUnsafe(bytes))
            assert(handle.hasLeftover, "an upgrading close must stage the read as leftover for the handshake, not drop it")
            handle.dequeueLeftover() match
                case Present(JsHandle.Leftover(buf, off, len)) =>
                    assert(java.util.Arrays.copyOfRange(buf, off, off + len).toSeq == bytes.toSeq, "staged leftover bytes did not match")
                case Absent => fail("upgrading close staged nothing")
            end match
        }

        "discards the bytes when the handle is not upgrading (ordinary close, unchanged behavior)" in {
            val driver = JsIoDriver.init()
            val handle = new JsHandle(inertSocket(), HandleId.next(0), Frame.internal)
            // handle.upgrading stays false (the default): an ordinary teardown close, not a STARTTLS upgrade window.
            driver.onInboundClosedDuringRead(handle, Span.fromUnsafe(Array[Byte](44, 55)))
            assert(!handle.hasLeftover, "an ordinary (non-upgrade) close must discard the read, not stage it as leftover")
        }
    }

end JsIoDriverUpgradeHandoffDropTest
