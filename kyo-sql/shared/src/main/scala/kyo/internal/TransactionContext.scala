package kyo.internal

import kyo.internal.mysql.MysqlConnection
import kyo.internal.postgres.PostgresConnection

/** Per-transaction state bound to a fiber via [[kyo.Local]].
  *
  * A [[TransactionContext]] is created when `SqlClient.transaction` is called and stored in a fiber-local
  * `Local[Maybe[TransactionContext]]`. Nested `transaction` calls detect the active context and use SAVEPOINTs instead of a fresh `BEGIN`.
  *
  * There are two concrete variants:
  *   - [[TransactionContext.Pg]] — wraps a [[PostgresConnection]]
  *   - [[TransactionContext.My]] — wraps a [[MysqlConnection]]
  *
  * The sealed hierarchy lets [[kyo.sql.SqlClient.transaction]] dispatch to the correct exchange (Postgres or MySQL) without a runtime cast.
  */
sealed private[kyo] trait TransactionContext:
    /** Nesting depth: 0 = outermost transaction (uses BEGIN/COMMIT/ROLLBACK); > 0 = nested savepoint. */
    def depth: Int

    /** Stack of active savepoint names, outermost-last (most-recent first). */
    def savepointStack: List[String]
end TransactionContext

object TransactionContext:

    /** Transaction context for a [[PostgresConnection]]. */
    final case class Pg(
        connection: PostgresConnection,
        depth: Int,
        savepointStack: List[String]
    ) extends TransactionContext

    /** Transaction context for a [[MysqlConnection]]. */
    final case class My(
        connection: MysqlConnection,
        depth: Int,
        savepointStack: List[String]
    ) extends TransactionContext

end TransactionContext
