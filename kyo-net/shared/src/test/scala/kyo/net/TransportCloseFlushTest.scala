package kyo.net

import kyo.*
import kyo.net.internal.transport.Connection as InternalConnection
import kyo.net.internal.transport.WriteState

/** A closing connection holds its descriptor until the writes queued at the close reach the peer, bounded by
  * [[NetConfig.closeFlushGrace]]. Runs over every registered backend with real sockets.
  *
  * The client is a kyo connection that never takes from its inbound channel: once that channel and both kernel buffers are full, the
  * server's WritePump parks on writability and no byte moves again. The grace runs on the transport's live clock, so the release is
  * awaited by polling with a bound far above the grace; the bound only turns a hang into a failure.
  */
class TransportCloseFlushTest extends Test:

    import AllowUnsafe.embrace.danger

    private def awaitCondition(bound: Duration)(cond: => Boolean)(using Frame): Boolean < Async =
        val deadline = java.lang.System.nanoTime() + bound.toNanos
        Loop(()) { _ =>
            if cond then Loop.done(true)
            else if java.lang.System.nanoTime() >= deadline then Loop.done(false)
            else Async.sleep(5.millis).andThen(Loop.continue(()))
        }
    end awaitCondition

    private def parkedOnWritable(conn: InternalConnection[?]): Boolean =
        conn.writeState match
            case WriteState.AwaitingWritable(_, _) | WriteState.Backpressured(_, _) => true
            case _                                                                  => false

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

    "a closing connection whose peer never reads releases its descriptor after closeFlushGrace" - eachBackend { transport =>
        val config   = NetConfig(closeFlushGrace = 200.millis.grace)
        val accepted = Promise.Unsafe.init[Connection, Any]()
        for
            listener <- transport.listen("127.0.0.1", 0, 128, config)(conn => accepted.completeDiscard(Result.succeed(conn))).safe.get
            _        <- Scope.ensure(Sync.defer(listener.close()))
            client   <- transport.connect("127.0.0.1", listener.port).safe.get
            _        <- Scope.ensure(Sync.defer(client.close()))
            server   <- accepted.safe.get.map(_.asInstanceOf[InternalConnection[?]])
            stalled  <- fillUntilStalled(server)
            _        <- Sync.defer(server.close())
            released <- awaitCondition(10.seconds)(server.isReleased)
        yield
            assert(stalled, "the server's WritePump never parked on a full socket, so the close would not exercise the flush bound")
            assert(released, "a close whose peer never reads must release the descriptor once a grace window passes with no write progress")
        end for
    }

    "a closing connection whose peer reads everything delivers every queued byte" - eachBackend { transport =>
        val config   = NetConfig(closeFlushGrace = 200.millis.grace)
        val accepted = Promise.Unsafe.init[Connection, Any]()
        val payload  = Array.tabulate[Byte](1024 * 1024)(i => (i % 251).toByte)
        for
            listener <- transport.listen("127.0.0.1", 0, 128, config)(conn => accepted.completeDiscard(Result.succeed(conn))).safe.get
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
