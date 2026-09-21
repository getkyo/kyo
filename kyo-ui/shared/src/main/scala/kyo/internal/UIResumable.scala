package kyo.internal

import kyo.*

/** A session whose frames outlive the transport that was carrying them.
  *
  * The live WebSocket session needs none of this: the socket is the order, and a socket that drops takes its session
  * with it. A view in an MCP Apps sandbox has neither property. Its baseline transport is a series of tool calls, which
  * the host may deliver out of order or repeat, and which cannot push at all, so what happened between two of them has
  * to be waiting somewhere. When the view later upgrades to a WebSocket, or falls back off one, the session carries on
  * rather than starting over.
  *
  * So the frames belong to the session and not to the transport. Outbound frames are numbered and kept until the peer
  * says it has them; a transport attaching later asks for everything after its cursor. Inbound events are numbered by
  * the peer and applied in that order, a repeat dropped and a gap held until it fills. A transport switch is a change of
  * who drains the session, which is what makes it lossless in both directions.
  *
  * What is deliberately not done: a frame is never dropped to make room. A `Replace` of one path is not superseded by a
  * `Replace` of another, so forgetting the oldest would silently lose an update. Past [[Limits.frames]] unacknowledged
  * frames the session ends instead, and the view mounts again on a fresh one, which renders everything.
  */
private[kyo] object UIResumable:

    /** One frame the session emitted, and where it sits in the session's order. */
    final case class Sent(seq: Long, payload: String) derives CanEqual

    /** What a session holds while nobody is draining it, and how far ahead of itself a peer may run.
      *
      * @param frames
      *   unacknowledged outbound frames kept before the session gives up on being resumable
      * @param pendingEvents
      *   inbound events held while the one before them is missing
      */
    final case class Limits(frames: Int = 256, pendingEvents: Int = 64) derives CanEqual:
        require(frames > 0, "kyo-ui: a resumable session keeps at least one frame")
        require(pendingEvents >= 0, "kyo-ui: a pending-event bound cannot be negative")
    end Limits

    /** What a transport draining a session gets. */
    enum Pull derives CanEqual:
        /** The frames after the cursor asked for, in order. */
        case Frames(frames: Chunk[Sent])

        /** The session is over; nothing more will come. */
        case Ended(reason: String)
    end Pull

    /** What became of one event a transport delivered. */
    enum Delivery derives CanEqual:
        /** In order: handed to the session, along with anything held behind it. */
        case Applied

        /** Already seen, so dropped rather than applied twice. */
        case Repeated

        /** Ahead of the event the session is waiting for, so held until that one arrives. */
        case Buffered

        /** The session took it no further, and says why. */
        case Refused(reason: String)
    end Delivery

    private val unresumable = "the view fell too far behind to be resumed"
    private val gapped      = "too many events arrived out of order"

    final private case class State(
        nextSeq: Long,
        log: Chunk[Sent],
        forgotten: Long,
        outWake: Fiber.Promise[Unit, Any],
        expected: Long,
        pending: Map[Long, String],
        ready: Chunk[String],
        inWake: Fiber.Promise[Unit, Any],
        ended: Maybe[String]
    )

    /** A session's frames, and the two cursors that make a transport switch lossless. */
    final class Session private[UIResumable] (
        limits: Limits,
        state: AtomicRef[State],
        ending: Fiber.Promise[Unit, Any]
    ):

        /** The channel the session runs over: what it sends is logged, what it reads is what arrived in order. */
        val channel: UIChannel =
            new UIChannel:
                def send(frame: String)(using Frame): Unit < (Async & Abort[Closed]) = append(frame)

                // One chunk per batch the session made ready, emitted as soon as it is ready. Stream.repeatPresent
                // would read the same way and then rechunk to its default 4096, which for a live source means the
                // session dispatches nothing until 4096 events have arrived or it ends.
                def received(using Frame): Stream[String, Async] =
                    Stream[String, Async]:
                        Loop.foreach:
                            nextReady.map {
                                case Present(batch) => Emit.valueWith(Chunk.from(batch))(Loop.continue)
                                case Absent         => Emit.valueWith(Chunk.empty[String])(Loop.done)
                            }

                def awaitClose(using Frame): Unit < Async = shared(ending)

        /** The frames after `cursor`, without waiting for more. */
        def since(cursor: Long)(using Frame): Pull < Sync = state.use(pullOf(_, cursor))

        /** The frames after `cursor`, waiting until there is at least one or the session ends.
          *
          * A poll bounds its own wait, since how long to hold one open is the transport's to decide.
          */
        def awaitSince(cursor: Long)(using Frame): Pull < Async =
            state.use { s =>
                pullOf(s, cursor) match
                    // The wake read here is the one this state carried, so a frame appended between the read and the
                    // wait completes it and is not missed.
                    case Pull.Frames(frames) if frames.isEmpty => shared(s.outWake).andThen(awaitSince(cursor))
                    case pull                                  => pull
            }

        /** Forgets the frames the peer says it has, and with them the session's ability to replay them. */
        def acknowledge(through: Long)(using Frame): Unit < Sync =
            state.updateAndGet { s =>
                if through <= s.forgotten then s
                else s.copy(log = s.log.filter(_.seq > through), forgotten = through)
            }.unit

        /** Hands the session one event the peer numbered `clientSeq`. */
        def deliver(clientSeq: Long, payload: String)(using Frame): Delivery < Sync =
            Fiber.Promise.initWith[Unit, Any] { fresh =>
                state.getAndUpdate { s =>
                    if s.ended.isDefined || clientSeq < s.expected then s
                    else if clientSeq > s.expected then
                        if s.pending.size >= limits.pendingEvents then s.copy(ended = Present(gapped))
                        else s.copy(pending = s.pending.updated(clientSeq, payload))
                    else
                        val (arrived, held, expected) = drain(s.pending, clientSeq + 1, Chunk(payload))
                        s.copy(expected = expected, pending = held, ready = s.ready.concat(arrived), inWake = fresh)
                }.map { before =>
                    before.ended match
                        case Present(reason) => Delivery.Refused(reason)
                        case Absent          =>
                            if clientSeq < before.expected then Delivery.Repeated
                            else if clientSeq > before.expected then
                                if before.pending.size >= limits.pendingEvents then
                                    wakeAll(before).andThen(Delivery.Refused(gapped))
                                else Delivery.Buffered
                            else before.inWake.completeDiscard(Result.succeed(())).andThen(Delivery.Applied)
                }
            }

        /** Ends the session, waking everyone waiting on it. */
        def end(reason: String)(using Frame): Unit < Sync =
            state.getAndUpdate(s => if s.ended.isDefined then s else s.copy(ended = Present(reason))).map { before =>
                if before.ended.isDefined then Kyo.unit else wakeAll(before)
            }

        /** Appends one frame to the log, or ends the session when it has held all it will hold. */
        private def append(payload: String)(using Frame): Unit < (Async & Abort[Closed]) =
            Fiber.Promise.initWith[Unit, Any] { fresh =>
                state.getAndUpdate { s =>
                    if s.ended.isDefined then s
                    else if s.log.size >= limits.frames then s.copy(ended = Present(unresumable))
                    else s.copy(nextSeq = s.nextSeq + 1, log = s.log.append(Sent(s.nextSeq, payload)), outWake = fresh)
                }.map { before =>
                    before.ended match
                        case Present(reason) => Abort.fail(closed(reason))
                        case Absent          =>
                            if before.log.size >= limits.frames then wakeAll(before).andThen(Abort.fail(closed(unresumable)))
                            else before.outWake.completeDiscard(Result.succeed(()))
                }
            }

        /** The events that arrived in order since the last read, waiting for one when there are none. */
        private def nextReady(using Frame): Maybe[Seq[String]] < Async =
            state.getAndUpdate(s => if s.ready.isEmpty then s else s.copy(ready = Chunk.empty)).map { before =>
                if before.ready.nonEmpty then Present(before.ready)
                else
                    before.ended match
                        case Present(_) => Absent
                        case Absent     => shared(before.inWake).andThen(nextReady)
            }

        /** Waits on a promise the session's other readers also wait on.
          *
          * A plain `get` links the waiter to the promise, so interrupting one waiter completes it with `Interrupted`
          * for every waiter after it. A session outlives its transports by design: the socket's draining fiber is
          * interrupted the moment the view falls back, and the poll that takes over waits on the same wake.
          */
        private def shared(promise: Fiber.Promise[Unit, Any])(using Frame): Unit < Async =
            promise.mask.map(_.get).unit

        // A cursor behind `forgotten` is served from `forgotten` rather than turned away. Nothing advances `forgotten`
        // except the view's own acknowledgement, so a cursor behind it names frames that view has already applied, and
        // what it has not applied is exactly what is still in the log. It arrives because a view makes calls
        // concurrently and each carries the cursor it had when it was made, so one issued earlier can be served after a
        // later one acknowledged more. Ending the session over that ends one that was never in trouble, and the view
        // cannot tell that apart from a session it really did fall out of, so it stops for good.
        private def pullOf(s: State, cursor: Long): Pull =
            val frames = s.log.filter(_.seq > cursor)
            s.ended match
                case Present(reason) if frames.isEmpty => Pull.Ended(reason)
                case _                                 => Pull.Frames(frames)
        end pullOf

        private def wakeAll(s: State)(using Frame): Unit < Sync =
            s.outWake.completeDiscard(Result.succeed(()))
                .andThen(s.inWake.completeDiscard(Result.succeed(())))
                .andThen(ending.completeDiscard(Result.succeed(())))

        private def closed(reason: String)(using frame: Frame): Closed = Closed("kyo-ui session", frame, reason)

        /** Takes the held events that now follow `next` in order, until one is missing. */
        @annotation.tailrec
        private def drain(
            pending: Map[Long, String],
            next: Long,
            arrived: Chunk[String]
        ): (Chunk[String], Map[Long, String], Long) =
            pending.get(next) match
                case Some(payload) => drain(pending.removed(next), next + 1, arrived.append(payload))
                case None          => (arrived, pending, next)

    end Session

    /** A fresh session: the first frame it sends is numbered 1, and the first event it expects is numbered 1. */
    def init(limits: Limits = Limits())(using Frame): Session < Sync =
        for
            outWake <- Fiber.Promise.init[Unit, Any]
            inWake  <- Fiber.Promise.init[Unit, Any]
            ending  <- Fiber.Promise.init[Unit, Any]
            state   <- AtomicRef.init(State(
                nextSeq = 1L,
                log = Chunk.empty,
                forgotten = 0L,
                outWake = outWake,
                expected = 1L,
                pending = Map.empty,
                ready = Chunk.empty,
                inWake = inWake,
                ended = Absent
            ))
        yield new Session(limits, state, ending)

end UIResumable
