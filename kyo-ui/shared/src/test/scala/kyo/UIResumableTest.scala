package kyo

import kyo.internal.UIResumable
import kyo.internal.UIResumable.Delivery
import kyo.internal.UIResumable.Pull
import kyo.internal.UIResumable.Sent

/** The frames belong to the session, so a transport that goes away takes nothing with it.
  *
  * The leaf that states the requirement is the last one: a view that starts on tool calls, upgrades to a socket, and
  * falls back off it sees every frame exactly once and in order, and the server sees every event exactly once and in
  * order, with no transport aware of the others.
  */
class UIResumableTest extends kyo.test.Test[Any]:

    private def payloads(pull: Pull)(using kyo.test.AssertScope, Frame): Chunk[String] =
        pull match
            case Pull.Frames(frames) => frames.map(_.payload)
            case other               => fail(s"expected frames, got $other")

    "frames are numbered from one, and a cursor of zero asks for all of them" in {
        for
            session <- UIResumable.init()
            _       <- session.channel.send("a")
            _       <- session.channel.send("b")
            pull    <- session.since(0)
        yield pull match
            case Pull.Frames(frames) => assert(frames == Chunk(Sent(1, "a"), Sent(2, "b")), frames.toString)
            case other               => fail(s"expected frames, got $other")
    }

    // A cursor behind what was acknowledged is not a view that fell behind: nothing moves `forgotten` except the view's
    // own acknowledgement, so a cursor behind it is one the view has already passed. It arrives because a view makes
    // calls concurrently and each carries the cursor it had when it was made, so a call issued earlier can be served
    // after a later one has acknowledged more. Answering it from where the session now starts is what it asked for;
    // telling it to mount again ends a session that was never in trouble.
    "acknowledging forgets what the peer has, and a cursor behind that is served from there" in {
        for
            session <- UIResumable.init()
            _       <- session.channel.send("a")
            _       <- session.channel.send("b")
            _       <- session.acknowledge(1)
            kept    <- session.since(1)
            behind  <- session.since(0)
        yield
            assert(payloads(kept) == Chunk("b"))
            assert(payloads(behind) == Chunk("b"), behind.toString)
    }

    // The shape that killed a live board: the view polls, acknowledges, and a call it made before that acknowledgement
    // lands afterwards. It has to be answered with what the view has not seen, not with an order to start over.
    "a call that overtakes an acknowledgement from the same view does not end the session" in {
        for
            session <- UIResumable.init()
            _       <- session.channel.send("render:1")
            _       <- session.channel.send("render:2")
            // The view applied both and acknowledged them, on the call that arrived first.
            _ <- session.acknowledge(2)
            _ <- session.channel.send("render:3")
            // The straggler: made when the view had only applied the first, and carrying that cursor.
            late <- session.since(1)
        yield assert(payloads(late) == Chunk("render:3"), late.toString)
    }

    "a pull with nothing after its cursor waits for the next frame rather than answering empty" in {
        for
            session <- UIResumable.init()
            waiting <- Fiber.initUnscoped(session.awaitSince(0))
            _       <- session.channel.send("first")
            pull    <- waiting.get
        yield assert(payloads(pull) == Chunk("first"), pull.toString)
    }

    "events are applied in the peer's order: a repeat is dropped and a gap waits for what is missing" in {
        for
            session <- UIResumable.init()
            reading <- Fiber.initUnscoped(session.channel.received.take(3).run)
            ahead   <- session.deliver(3, "third")
            first   <- session.deliver(1, "first")
            repeat  <- session.deliver(1, "first again")
            second  <- session.deliver(2, "second")
            seen    <- reading.get
        yield
            assert(ahead == Delivery.Buffered, ahead.toString)
            assert(first == Delivery.Applied, first.toString)
            assert(repeat == Delivery.Repeated, repeat.toString)
            assert(second == Delivery.Applied, second.toString)
            assert(seen == Chunk("first", "second", "third"), seen.toString)
    }

    "a session that fell too far behind ends rather than forgetting a frame" in {
        for
            session <- UIResumable.init(UIResumable.Limits(frames = 2))
            _       <- session.channel.send("a")
            _       <- session.channel.send("b")
            refused <- Abort.run[Closed](session.channel.send("c"))
            after   <- session.since(2)
        yield
            assert(refused.isFailure, refused.toString)
            assert(after == Pull.Ended("the view fell too far behind to be resumed"), after.toString)
    }

    "a peer running too far ahead of itself ends the session rather than holding everything" in {
        for
            session <- UIResumable.init(UIResumable.Limits(pendingEvents = 1))
            held    <- session.deliver(5, "five")
            refused <- session.deliver(6, "six")
            after   <- session.deliver(1, "one")
        yield
            assert(held == Delivery.Buffered, held.toString)
            assert(refused == Delivery.Refused("too many events arrived out of order"), refused.toString)
            assert(after == Delivery.Refused("too many events arrived out of order"), after.toString)
    }

    // A session outlives its transports by design: the socket's draining fiber is interrupted the moment the view falls
    // back, and the poll that takes over waits on the same wake. A plain get would hand that poll the interruption.
    "one reader being interrupted leaves the next one able to wait" in {
        for
            session <- UIResumable.init()
            first   <- Fiber.initUnscoped(session.awaitSince(0))
            _       <- first.interrupt
            _       <- first.getResult
            second  <- Fiber.initUnscoped(session.awaitSince(0))
            _       <- session.channel.send("after the interruption")
            pull    <- second.get
        yield assert(payloads(pull) == Chunk("after the interruption"), pull.toString)
    }

    "one reader of the session's events being interrupted leaves the next one able to read" in {
        for
            session <- UIResumable.init()
            first   <- Fiber.initUnscoped(session.channel.received.run)
            _       <- first.interrupt
            _       <- first.getResult
            second  <- Fiber.initUnscoped(session.channel.received.run)
            _       <- session.deliver(1, "after the interruption")
            _       <- session.end("done")
            seen    <- second.get
        yield assert(seen == Chunk("after the interruption"), seen.toString)
    }

    "ending the session wakes a pull that was waiting for a frame" in {
        for
            session <- UIResumable.init()
            waiting <- Fiber.initUnscoped(session.awaitSince(0))
            _       <- session.end("the view said it was going")
            pull    <- waiting.get
        yield assert(pull == Pull.Ended("the view said it was going"), pull.toString)
    }

    "ending the session ends the frames its channel reads" in {
        for
            session <- UIResumable.init()
            reading <- Fiber.initUnscoped(session.channel.received.run)
            _       <- session.deliver(1, "one")
            _       <- session.end("done")
            seen    <- reading.get
        yield assert(seen == Chunk("one"), seen.toString)
    }

    // The requirement itself: full reactivity over tool calls and over a socket, with a switch between them that loses
    // nothing. Neither transport here knows the other exists; each only asks the session what happened after its cursor
    // and hands it what the view did.
    "a transport switch loses nothing and repeats nothing, in either direction" in {
        for
            session <- UIResumable.init()
            // The view starts on tool calls: it polls, gets two frames, and sends one event.
            _      <- session.channel.send("render:1")
            _      <- session.channel.send("render:2")
            polled <- session.awaitSince(0)
            _      <- session.deliver(1, "click")
            // The view upgrades. The socket resumes from what the poll acknowledged, so the frame produced while the
            // upgrade was in flight is still there for it.
            _        <- session.acknowledge(2)
            _        <- session.channel.send("render:3")
            _        <- session.channel.send("render:4")
            onSocket <- session.awaitSince(2)
            _        <- session.deliver(2, "type")
            // The socket drops mid-session and the view falls back, resuming from what the socket acknowledged. The
            // event it repeats on the way (the host retried it) is dropped rather than applied twice.
            _        <- session.acknowledge(3)
            _        <- session.channel.send("render:5")
            repeated <- session.deliver(2, "type")
            backOff  <- session.awaitSince(3)
            _        <- session.deliver(3, "submit")
            _        <- session.end("done")
            events   <- session.channel.received.run
        yield
            assert(payloads(polled) == Chunk("render:1", "render:2"), polled.toString)
            assert(payloads(onSocket) == Chunk("render:3", "render:4"), onSocket.toString)
            assert(payloads(backOff) == Chunk("render:4", "render:5"), backOff.toString)
            assert(repeated == Delivery.Repeated, repeated.toString)
            assert(events == Chunk("click", "type", "submit"), events.toString)
        end for
    }

end UIResumableTest
