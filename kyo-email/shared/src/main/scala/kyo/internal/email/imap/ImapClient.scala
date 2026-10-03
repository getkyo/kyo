package kyo.internal.email.imap

import kyo.*
import kyo.EmailTransportException.Kind

/** Where an `EmailReceive` verb finds its session: `config` for the receive loop, which opens a connection of its own, and `session` for
  * every other verb.
  */
sealed abstract private[kyo] class ImapClient:
    def config: EmailImapConfig
    def session(method: String)(using Frame): ImapSession < (Async & Abort[EmailConnectFailure])
end ImapClient

private[kyo] object ImapClient:

    /** The session of `EmailReceive.run`: opened by the first verb that needs it, opened again by the first verb after a failure closed
      * it, and logged out by `close`.
      *
      * `opening` admits one opener at a time, so concurrent first verbs share one login. `close` marks the slot ended before it logs out,
      * and an open that completes after that closes what it opened and fails, so no session outlives `run` (a fiber `v` forked and left
      * running is the only caller that can arrive after it).
      */
    final class Lazy private[ImapClient] (val config: EmailImapConfig, opening: Meter, slot: AtomicRef[Slot]) extends ImapClient:

        def session(method: String)(using Frame): ImapSession < (Async & Abort[EmailConnectFailure]) =
            current(method).map {
                case Present(open) => open
                case Absent        =>
                    Abort.run[Closed](opening.run(current(method).map {
                        case Present(open) => open
                        case Absent        => ImapSession.open(method, config).ensureMap(stored(method, _))
                    })).map {
                        case Result.Success(open) => open
                        case Result.Failure(_)    => bug("the opening mutex is never closed")
                        case Result.Panic(ex)     => Abort.panic(ex)
                    }
            }

        def close(using Frame): Unit < Async =
            slot.getAndSet(Slot(Absent, ended = true)).map(_.session.fold(Kyo.unit)(_.close))

        private def current(method: String)(using Frame): Maybe[ImapSession] < (Sync & Abort[EmailTransportException]) =
            slot.get.map { held =>
                if held.ended then Abort.fail(ended(method))
                else
                    held.session match
                        case Present(open) => open.closed.map(closed => if closed then Absent else Present(open))
                        case Absent        => Absent
            }

        private def stored(method: String, opened: ImapSession)(using Frame): ImapSession < (Async & Abort[EmailTransportException]) =
            slot.getAndUpdate(held => if held.ended then held else held.copy(session = Present(opened))).map { previous =>
                if previous.ended then opened.close.andThen(Abort.fail(ended(method))) else opened
            }

        private def ended(method: String)(using Frame): EmailTransportException =
            EmailTransportException(method, Kind.ConnectionClosed(Absent), config.host, config.resolvedPort, Absent)
    end Lazy

    /** The receive loop's own session, which the handler's verbs use. */
    final class Bound(val config: EmailImapConfig, held: ImapSession) extends ImapClient:
        def session(method: String)(using Frame): ImapSession < (Async & Abort[EmailConnectFailure]) = held

    final case class Slot(session: Maybe[ImapSession], ended: Boolean)

    def init(config: EmailImapConfig)(using Frame): Lazy < Sync =
        for
            opening <- Meter.initMutexUnscoped
            slot    <- AtomicRef.init(Slot(Absent, ended = false))
        yield new Lazy(config, opening, slot)

end ImapClient
