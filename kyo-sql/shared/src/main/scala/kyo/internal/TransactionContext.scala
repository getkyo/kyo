package kyo.internal

import kyo.*
import kyo.db.Connection

/** The transaction a fiber is currently inside, held in a [[kyo.Local]].
  *
  * `SqlClient.transaction` installs one for the duration of its body, and every statement checks for it before reaching for the pool: finding
  * one means running on the connection the transaction pinned, so the statements a caller writes inside the body need no receiver threading to
  * end up on the same session.
  *
  * One shape covers both engines. The connection is a [[Connection]], so `transaction` neither dispatches on which backend it is talking to
  * nor keeps a case per engine; a client that needs the concrete connection for an engine-only operation narrows [[connection]] itself.
  *
  * @param client
  *   the client whose pool [[connection]] came from, compared by reference and never by value. A [[kyo.Local]] belongs to the FIBER, so without
  *   this a statement issued on a different client inside the body would be routed onto this connection: a different pool, pointing at a possibly
  *   different server, so the statement would run against the wrong database rather than merely failing to join the transaction. Two clients built
  *   from one URL are still two pools and two sets of sessions, which is why identity rather than equality is the test. Read it only through
  *   `SqlClient.enclosingTransaction`, which applies the comparison; nothing else should touch `SqlClient.txLocal` directly.
  * @param connection
  *   the session every statement in the body runs on
  * @param depth
  *   nesting depth; `0` is the outermost transaction, which owns the real `BEGIN` and `COMMIT`, and anything greater is a savepoint
  * @param savepointStack
  *   the savepoint names currently established, most recent first
  * @param meter
  *   the session's statement mutex. Every statement routed onto [[connection]] runs holding its single permit, so
  *   concurrent fibers inside the body queue on the session instead of interleaving protocol frames on one socket.
  *   Nested savepoints share the outer transaction's meter, because they share its connection.
  * @param failed
  *   the statement failure this transaction is carrying, if one has happened and nothing has rolled back past it.
  *
  * Shared by reference across the nest rather than copied per level, because the condition belongs to the transaction and not to the depth
  * that happened to see it. A statement that fails inside a transaction leaves it unable to commit meaningfully: one engine refuses every
  * later statement and turns the commit into a rollback, and the other carries on and commits the survivors, so the SAME program with a
  * handled error committed different data depending on the backend, and told the caller nothing either way.
  *
  * Set when a statement fails, and cleared when a savepoint rollback succeeds, which is exactly the operation that makes the transaction
  * usable again. That is what keeps a nested `transaction` working as the way to recover: it rolls back to its own savepoint, clears this,
  * and the outer body carries on.
  */
final private[kyo] case class TransactionContext(
    client: SqlClient,
    connection: Connection,
    depth: Int,
    savepointStack: Chunk[String],
    meter: Meter,
    failed: AtomicRef[Maybe[String]]
)
