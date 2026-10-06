package kyo.internal.email.imap

import kyo.*
import kyo.EmailReceive.Start
import kyo.EmailTransportException.Kind
import kyo.internal.email.imap.ImapSession.Listed

/** The receive loop of `EmailReceive.receive`: a session of its own, deliveries in UID order, IDLE or polling between them, and
  * reconnection.
  *
  * The loop's state is the next UID to deliver and the mailbox's UIDVALIDITY, held outside every session. A reconnect resumes from that
  * state, so nothing handled is delivered again and nothing unhandled is lost. The handler runs outside every recovery here, so its own
  * failure ends the loop as it was raised. A failed token computation is never retried, since computing it again would fail the same way.
  */
private[kyo] object ImapReceiver:

    inline val Method = "receive"

    // What outlives a session: the next UID to deliver and the UIDVALIDITY, both known once the first session's SELECT is checked, and
    // the number of completed rounds, a change in which restarts the reconnect schedule.
    final private case class Resume(next: Maybe[Long], validity: Maybe[Email.UidValidity], rounds: Long)

    def run[E2, S](config: EmailImapConfig, start: Start)(
        handler: (ImapSession, EmailReceive.InboxEvent) => Unit < (Async & Abort[E2] & S)
    )(using Frame): Unit < (Async & Abort[EmailReceiveFailure | E2] & S) =
        val mailbox = start match
            case Start.New(name)  => name
            case Start.All(name)  => name
            case Start.After(uid) => uid.mailbox
        AtomicRef.init(Resume(Absent, Absent, 0)).map { resume =>
            Loop(config.reconnect, 0L) { (schedule, rounds) =>
                session(config, mailbox, start, resume)(handler).map { failure =>
                    resume.get.map { state =>
                        // Until the first session's SELECT is checked nothing was received, so its failures are the caller's.
                        if state.validity.isEmpty || !retried(failure) then Abort.fail(failure)
                        else
                            // A whole round is what restarts the schedule: a server that accepts every reconnect and fails every FETCH
                            // would otherwise be retried forever.
                            val remaining = if state.rounds != rounds then config.reconnect else schedule
                            Clock.now.map { now =>
                                remaining.next(now) match
                                    case Absent                 => Abort.fail(failure)
                                    case Present((delay, rest)) => Async.sleep(delay).andThen(Loop.continue(rest, state.rounds))
                            }
                    }
                }
            }
        }
    end run

    // One connection, from opening it to the failure that ends it, answered as a value. The session is registered for release as it is
    // acquired, so an interrupt or the handler's `E2` leaving through the Scope drops the connection. It is dropped, not logged out:
    // mid-IDLE a LOGOUT would not be understood.
    private def session[E2, S](
        config: EmailImapConfig,
        mailbox: Email.MailboxName,
        start: Start,
        resume: AtomicRef[Resume]
    )(handler: (ImapSession, EmailReceive.InboxEvent) => Unit < (Async & Abort[E2] & S))(using
        Frame
    ): EmailReceiveFailure < (Async & Abort[E2] & S) =
        Scope.run {
            Abort.run[EmailConnectFailure](Scope.acquireRelease(ImapSession.open(Method, config))(_.abandon)).map {
                case Result.Success(session) =>
                    Abort.run[EmailReceiveFailure](selected(session, config, mailbox, start, resume)).map {
                        case Result.Success((validity, first)) =>
                            rounds(session, config, mailbox, validity, first, resume)(handler)
                        case Result.Failure(failure) => failure
                        case Result.Panic(ex)        => Abort.panic(ex)
                    }
                case Result.Failure(failure) => failure
                case Result.Panic(ex)        => Abort.panic(ex)
            }
        }

    // The mailbox selected, its UIDVALIDITY checked against the one every earlier session reported, and the first UID to deliver, both
    // recorded in `resume`.
    private def selected(
        session: ImapSession,
        config: EmailImapConfig,
        mailbox: Email.MailboxName,
        start: Start,
        resume: AtomicRef[Resume]
    )(using Frame): (Email.UidValidity, Long) < (Async & Abort[EmailReceiveFailure]) =
        session.selectNow(Method, mailbox).map { (selection, uidNext) =>
            resume.get.map { state =>
                val expected = state.validity.orElse(start match
                    case Start.After(uid) => Present(uid.validity)
                    case _                => Absent)
                expected match
                    case Present(known) if known != selection.validity =>
                        Abort.fail(EmailUidValidityChangedException(Method, mailbox, known, selection.validity))
                    case _ =>
                        val first = state.next.orElse(start match
                            case Start.New(_)     => uidNext
                            case Start.All(_)     => Present(1L)
                            case Start.After(uid) => Present(uid.value + 1))
                        first match
                            case Present(uid) =>
                                resume.set(state.copy(next = Present(uid), validity = Present(selection.validity)))
                                    .andThen((selection.validity, uid))
                            case Absent =>
                                Abort.fail(EmailTransportException(
                                    Method,
                                    Kind.Incomplete("UIDNEXT"),
                                    config.host,
                                    config.resolvedPort,
                                    Absent
                                ))
                        end match
                end match
            }
        }

    // Rounds on one session until one fails: a listing, a delivery per listed UID, and the wait.
    private def rounds[E2, S](
        session: ImapSession,
        config: EmailImapConfig,
        mailbox: Email.MailboxName,
        validity: Email.UidValidity,
        first: Long,
        resume: AtomicRef[Resume]
    )(handler: (ImapSession, EmailReceive.InboxEvent) => Unit < (Async & Abort[E2] & S))(using
        Frame
    ): EmailReceiveFailure < (Async & Abort[E2] & S) =
        Loop(first) { next =>
            session.announcements.map { mark =>
                listing(session, mailbox, validity, next, resume)(handler).map { (delivered, stopped) =>
                    stopped.fold(
                        Abort.run[EmailReceiveFailure](session.reselect(Method, mailbox, validity).andThen(await(
                            session,
                            config,
                            mailbox,
                            mark
                        ))).map {
                            case Result.Success(_) =>
                                resume.updateAndGet(state => state.copy(rounds = state.rounds + 1)).andThen(Loop.continue(delivered))
                            case Result.Failure(failure) => Loop.done[Long, EmailReceiveFailure](failure)
                            case Result.Panic(ex)        => Abort.panic(ex)
                        }
                    )(Loop.done[Long, EmailReceiveFailure](_))
                }
            }
        }

    // A listing line is `* n FETCH (UID n RFC822.SIZE n)`, 70 octets with ten-digit numbers and a twenty-digit size; the rest is room for
    // the flags a server may add to it unsolicited.
    private inline val ListingLineCost = 128L

    // A window's reply is at most about 1.3 MB whatever maxResponseLength allows, so a round holds that much of the listing at a time.
    private inline val MaxWindow = 10000L

    // One round's deliveries from `next`, answering the next UID to deliver and the failure that stopped the round, if one did. A mailbox
    // of at most one window of messages is listed in one `UID FETCH next:*`, whose reply then stays within maxResponseLength. A larger one
    // is listed a window of sequence numbers at a time, each delivered before the next is asked: a single listing of it would grow with
    // the pending messages past maxResponseLength and fail every round. Sequence numbers are used, not UID ranges, because a sparse
    // mailbox leaves UID ranges empty, each costing a round trip.
    private def listing[E2, S](
        session: ImapSession,
        mailbox: Email.MailboxName,
        validity: Email.UidValidity,
        next: Long,
        resume: AtomicRef[Resume]
    )(handler: (ImapSession, EmailReceive.InboxEvent) => Unit < (Async & Abort[E2] & S))(using
        Frame
    ): (Long, Maybe[EmailReceiveFailure]) < (Async & Abort[E2] & S) =
        val size = Math.min(MaxWindow, Math.max(1L, session.maxResponseLength / ListingLineCost))
        session.messages.map { count =>
            if count <= size then
                Abort.run[EmailReceiveFailure](session.pending(Method, mailbox, validity, next)).map {
                    case Result.Success(listed)  => deliver(session, mailbox, validity, listed, next, resume)(handler)
                    case Result.Failure(failure) => (next, Present(failure))
                    case Result.Panic(ex)        => Abort.panic(ex)
                }
            else
                Loop(next, Maybe.empty[Long]) { (next, cursor) =>
                    Abort.run[EmailReceiveFailure](step(session, mailbox, validity, size, next, cursor)).map {
                        case Result.Success(Step.Done)               => Loop.done((next, Maybe.empty[EmailReceiveFailure]))
                        case Result.Success(Step.Moved)              => Loop.continue(next, Maybe.empty[Long])
                        case Result.Success(Step.Rows(last, listed)) =>
                            deliver(session, mailbox, validity, listed, next, resume)(handler).map {
                                case (delivered, Absent) => Loop.continue(delivered, Present(last + 1))
                                case stopped             => Loop.done(stopped)
                            }
                        case Result.Failure(failure) => Loop.done((next, Present(failure)))
                        case Result.Panic(ex)        => Abort.panic(ex)
                    }
                }
        }
    end listing

    // What one window of a round found: the round is over, the sequence numbers moved under the cursor, or the messages from the cursor
    // to sequence number `last`.
    private enum Step derives CanEqual:
        case Done
        case Moved
        case Rows(last: Long, listed: Chunk[(Email.Uid, Listed)])
    end Step

    // The window at `cursor`, the sequence number after the previous window, or at the first message whose UID is at least `next` when
    // there was none. An expunge the server reported since shifts later messages to lower sequence numbers, so a message not yet
    // delivered could sit below the cursor: the window starts one message early, at most at the last one, and the round finds its place
    // again unless that message's UID is below `next`. The same check ends the round only when no pending message slid below it.
    private def step(
        session: ImapSession,
        mailbox: Email.MailboxName,
        validity: Email.UidValidity,
        size: Long,
        next: Long,
        cursor: Maybe[Long]
    )(using Frame): Step < (Async & Abort[EmailReceiveFailure]) =
        def rows(at: Long, window: Chunk[(Long, Email.Uid, Listed)], last: Long) =
            Step.Rows(last, window.filter((sequence, uid, _) => sequence >= at && uid.value >= next).map((_, uid, listed) => (uid, listed)))
        session.reselect(Method, mailbox, validity).andThen(session.messages).map { count =>
            cursor match
                case Absent =>
                    boundary(session, count, next).map { at =>
                        if at > count then Step.Done
                        else
                            val last = Math.min(at + size - 1, count)
                            session.window(Method, mailbox, validity, at, last).map(rows(at, _, last))
                    }
                case Present(at) =>
                    if count == 0 then Step.Done
                    else
                        val check = Math.min(at - 1, count)
                        val last  = Math.min(at + size - 1, count)
                        session.window(Method, mailbox, validity, check, last).map { window =>
                            if !window.exists((sequence, uid, _) => sequence == check && uid.value < next) then Step.Moved
                            else if at > count then Step.Done
                            else rows(at, window, last)
                        }
        }
    end step

    // The lowest sequence number whose UID is at least `next`, or `count + 1` when there is none: UIDs ascend with sequence numbers
    // (RFC 9051 section 2.3.1.1), so a binary search finds it in about log2(count) round trips.
    private def boundary(session: ImapSession, count: Long, next: Long)(using Frame): Long < (Async & Abort[EmailReceiveFailure]) =
        Loop(1L, count + 1) { (low, high) =>
            if low >= high then Loop.done(low)
            else
                val middle = low + (high - low) / 2
                session.uidAt(Method, middle).map { uid =>
                    if uid >= next then Loop.continue(low, middle) else Loop.continue(middle + 1, high)
                }
        }

    // Each listed UID's message, fetched only once the one before it was handled, so a drop costs at most one download. A message whose
    // listed size is unreadable or past maxResponseLength is delivered as Unreadable without its body. `resume` moves past each UID as its
    // handler returns. Answers the next UID to deliver and the failure that stopped the round, if one did; the handler's own failure is
    // not caught here.
    private def deliver[E2, S](
        session: ImapSession,
        mailbox: Email.MailboxName,
        validity: Email.UidValidity,
        listed: Chunk[(Email.Uid, Listed)],
        next: Long,
        resume: AtomicRef[Resume]
    )(handler: (ImapSession, EmailReceive.InboxEvent) => Unit < (Async & Abort[E2] & S))(using
        Frame
    ): (Long, Maybe[EmailReceiveFailure]) < (Async & Abort[E2] & S) =
        import EmailReceive.InboxEvent.Unreadable
        val max = session.maxResponseLength.bytes
        Loop(0, next) { (index, next) =>
            if index == listed.size then Loop.done((next, Maybe.empty[EmailReceiveFailure]))
            else
                val (uid, size) = listed(index)
                def advanced    =
                    resume.updateAndGet(_.copy(next = Present(uid.value + 1))).andThen(Loop.continue(index + 1, uid.value + 1))
                def handled(event: EmailReceive.InboxEvent) = handler(session, event).andThen(advanced)
                def stopped(failure: EmailReceiveFailure)   =
                    Loop.done[Int, Long, (Long, Maybe[EmailReceiveFailure])]((next, Present(failure)))
                def fetched =
                    Abort.run[EmailReceiveFailure](session.reselect(Method, mailbox, validity).andThen(session.fetchOne(
                        Method,
                        mailbox,
                        validity,
                        uid.value
                    ))).map {
                        case Result.Success(Present(event)) => handled(event)
                        case Result.Success(Absent)         => advanced
                        case Result.Failure(failure)        => stopped(failure)
                        case Result.Panic(ex)               => Abort.panic(ex)
                    }
                def within(octets: Long) =
                    if octets > max.toBytes then handled(Unreadable(uid, Unreadable.Item.TooLarge(octets.bytes, max))) else fetched
                size match
                    case Listed.Octets(octets) => within(octets)
                    case Listed.Unreadable     => handled(Unreadable(uid, Unreadable.Item.Size))
                    case Listed.Unsized        =>
                        // A flag update the server sent in the listing can name a message the listing itself did not reach, so the size
                        // is asked for alone before the message is taken for one without a size.
                        Abort.run[EmailReceiveFailure](session.reselect(Method, mailbox, validity).andThen(session.sizeOf(
                            Method,
                            uid.value
                        ))).map {
                            case Result.Success(Present(Listed.Octets(octets))) => within(octets)
                            case Result.Success(Present(_))                     => handled(Unreadable(uid, Unreadable.Item.Size))
                            case Result.Success(Absent)                         => advanced
                            case Result.Failure(failure)                        => stopped(failure)
                            case Result.Panic(ex)                               => Abort.panic(ex)
                        }
                end match
        }
    end deliver

    // No wait when an EXISTS was read since the round's `mark`, in any reply or the SELECT of a reselect: RFC 9051 section 5.2 has a
    // server announce a new message in whatever reply it is writing, and IDLE reports only what arrives after it starts. Otherwise IDLE
    // until the server announces a message, or poll after `pollInterval` on a server that cannot idle.
    private def await(session: ImapSession, config: EmailImapConfig, mailbox: Email.MailboxName, mark: Long)(using
        Frame
    ): Unit < (Async & Abort[EmailReceiveFailure]) =
        session.announcements.map { now =>
            if now != mark then ()
            else
                session.idles.map { idles =>
                    if idles then session.idle(Method, mailbox, config.idleRenewal, mark) else Async.sleep(config.pollInterval)
                }
        }

    // The connection, a dropped IDLE and a temporary refusal (RFC 5530 UNAVAILABLE) may pass; every other failure is the caller's.
    private def retried(failure: EmailReceiveFailure): Boolean =
        failure match
            case _: EmailConnectException      => true
            case _: EmailTransportException    => true
            case _: EmailIdleDroppedException  => true
            case ex: EmailImapCommandException => ex.responseCode == Present(EmailReceive.ResponseCode.Unavailable)
            case _                             => false

end ImapReceiver
