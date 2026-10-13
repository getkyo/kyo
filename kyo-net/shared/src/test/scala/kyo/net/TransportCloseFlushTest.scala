package kyo.net

import kyo.*
import kyo.net.internal.transport.Connection as InternalConnection
import kyo.net.internal.transport.WriteState

/** A closing connection holds its descriptor until the writes queued at the close reach the peer, bounded by
  * [[NetConfig.closeFlushGrace]]. Runs over every registered backend with real sockets, each leaf on a transport whose graces run on a
  * controlled clock, so a grace expires only when the leaf advances it.
  *
  * The client is a kyo connection that never takes from its inbound channel: once that channel and both kernel buffers are full, the
  * server's WritePump parks on writability and no byte moves again.
  */
class TransportCloseFlushTest extends Test:

    import AllowUnsafe.embrace.danger

    private def parkedOnWritable(conn: InternalConnection[?]): Boolean =
        conn.writeState match
            case WriteState.AwaitingWritable(_, _) | WriteState.Backpressured(_, _) => true
            case _                                                                  => false

    /** Polls a condition that settles on the transport's own carriers; the leaf timeout turns one that never holds into a failure. */
    private def awaitState(cond: => Boolean)(using Frame): Unit < Async =
        Loop(()) { _ =>
            if cond then Loop.done(())
            else Async.sleep(1.millis).andThen(Loop.continue(()))
        }

    /** Offer spans until the outbound channel is full while the pump is parked on a socket the peer no longer drains. */
    private def fillUntilStalled(conn: InternalConnection[?])(using Frame): Boolean < Async =
        val span = Span.fromUnsafe(Array.fill[Byte](64 * 1024)(7))
        Loop(0) { offered =>
            if offered > 16 * 1024 then Loop.done(false)
            else
                conn.outbound.offer(span) match
                    case Result.Success(true)                            => Loop.continue(offered + 1)
                    case Result.Success(false) if parkedOnWritable(conn) => Loop.done(true)
                    case Result.Success(false)                           => Async.sleep(1.millis).andThen(Loop.continue(offered))
                    case _                                               => Loop.done(false)
        }
    end fillUntilStalled

    "a closing connection whose peer never reads releases its descriptor once closeFlushGrace passes on the transport's clock" -
        eachBackendOnClock { (transport, control) =>
            val grace             = 7.seconds
            val nonTerminationCap = (timeout.toMillis / 100 / 2).toInt
            val config            = NetConfig(closeFlushGrace = grace.grace)
            val accepted          = Promise.Unsafe.init[Connection, Any]()
            for
                listener <-
                    transport.listen("127.0.0.1", 0, 128, config)(conn => accepted.completeDiscard(Result.succeed(conn))).safe.get
                _          <- Scope.ensure(Sync.defer(listener.close()))
                client     <- transport.connect("127.0.0.1", listener.port).safe.get
                _          <- Scope.ensure(Sync.defer(client.close()))
                server     <- accepted.safe.get.map(_.asInstanceOf[InternalConnection[?]])
                firstStall <- fillUntilStalled(server)
                // Quiescence before the close: once the client's ReadPump is parked on its full inbound channel no read is armed, so no
                // byte leaves the client's receive buffer again, and the second fill parks the server against that filling buffer.
                _      <- awaitState(client.inbound.pendingPuts().getOrElse(0) > 0)
                second <- fillUntilStalled(server)
                _ = assert(
                    firstStall && second,
                    "the server's WritePump never parked on a full socket, so the close would not exercise the flush bound"
                )
                _ <- Sync.defer(server.close())
                // How many windows still see the segments in flight at the close drain is kernel timing, and the kernel can take the whole
                // tail between the stall and the close, after which an immediate release is correct. So this leaf asserts only that the
                // release comes; the exact window rule is pinned on a scripted driver, where no kernel moves bytes. The cap is half the leaf
                // budget at the about 100 ms of real time each advance yields, so a release that never comes fails with the count.
                windows <- Loop(0) { windows =>
                    if server.isReleased || windows >= nonTerminationCap then Loop.done(windows)
                    else
                        armedThenAdvance(control, s"close-flush grace (window ${windows + 1})", grace)
                            .andThen(Loop.continue(windows + 1))
                }
            yield assert(
                server.isReleased,
                s"a close whose peer never reads must release the descriptor; still held after $windows windows"
            )
            end for
        }

    // The transport's clock is never advanced, so the close-flush grace cannot end the connection: only the flush can.
    "a closing connection whose peer reads everything delivers every queued byte" - eachBackendOnClock { (transport, _) =>
        val accepted = Promise.Unsafe.init[Connection, Any]()
        val payload  = Array.tabulate[Byte](1024 * 1024)(i => (i % 251).toByte)
        for
            listener <- transport.listen("127.0.0.1", 0, 128)(conn => accepted.completeDiscard(Result.succeed(conn))).safe.get
            _        <- Scope.ensure(Sync.defer(listener.close()))
            client   <- transport.connect("127.0.0.1", listener.port).safe.get
            _        <- Scope.ensure(Sync.defer(client.close()))
            server   <- accepted.safe.get
            sendThenClose = Loop(0) { off =>
                if off >= payload.length then Loop.done(())
                else
                    val len = math.min(64 * 1024, payload.length - off)
                    server.outbound.safe.put(Span.from(payload.slice(off, off + len))).andThen(Loop.continue(off + len))
            }.andThen(Sync.defer(server.close()))
            drain = Loop(Array.emptyByteArray) { acc =>
                if acc.length >= payload.length then Loop.done(acc)
                else client.inbound.safe.take.map(span => Loop.continue(acc ++ span.toArray))
            }
            (_, received) <- Async.zip(sendThenClose, drain)
            afterEnd      <- Abort.run[Closed](client.inbound.safe.take)
        yield
            assert(received.sameElements(payload), s"every queued byte must reach the peer in order; got ${received.length} bytes")
            assert(afterEnd.isFailure, s"the stream must end after the payload; got $afterEnd")
        end for
    }

end TransportCloseFlushTest
