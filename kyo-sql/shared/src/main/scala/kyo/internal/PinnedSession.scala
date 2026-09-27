package kyo.internal

import kyo.*
import kyo.db.Connection

/** The session a fiber's statements are pinned to: the connection, its statement mutex, and the refusal a statement gets once the scope
  * that pinned it has ended.
  *
  * `SqlClient.transaction` and `SqlClient.withAdvisoryLock` each pin one, and the locals that publish them are inheritable, so a fiber the
  * body forked keeps the session after the body returned. `ended` is what makes that safe. Set inside the mutex in the same critical section
  * as the COMMIT, ROLLBACK or unlock that ends the scope, and read inside the mutex by every statement, it leaves exactly two orders: a
  * statement that got the permit first ran inside the scope, and one that queued behind the control statement is refused when it gets the
  * permit. Without it the late statement runs on a connection the pool has already leased to someone else, inside a transaction it was
  * never part of, or under no lock at all.
  *
  * @param connection
  *   the session every statement in the scope runs on
  * @param meter
  *   the session's statement mutex. Every statement routed onto [[connection]] runs holding its single permit, so concurrent fibers inside
  *   the body queue on the session instead of interleaving protocol frames on one socket.
  * @param ended
  *   the refusal a statement gets once the scope that pinned this session is over, [[Absent]] while it is open
  * @param host
  *   the session this one was opened inside: a lock taken inside a transaction, or a transaction opened inside a lock, shares the host's
  *   [[connection]] and [[meter]]. The host's end releases the connection under this scope, so this session is over when either scope is.
  */
final private[kyo] case class PinnedSession(
    connection: Connection,
    meter: Meter,
    ended: AtomicRef[Maybe[SqlException]],
    host: Maybe[PinnedSession]
):

    /** The refusal to raise, or [[Absent]] while this scope and every host it sits on are still open. */
    def endedWith(using Frame): Maybe[SqlException] < Sync =
        ended.get.flatMap {
            case Present(e) => Present(e)
            case Absent     => hostEndedWith
        }

    /** [[endedWith]] for the hosts alone: what a scope's own closing statement checks, since it records its own end first. */
    def hostEndedWith(using Frame): Maybe[SqlException] < Sync =
        host match
            case Present(h) => h.endedWith
            case Absent     => Maybe.empty
end PinnedSession
