package kyo.internal.mysql.exchange

import kyo.*
import kyo.SqlException
import kyo.internal.mysql.*

/** Cancels a running MySQL query by issuing `KILL QUERY <connectionId>` on a separate connection.
  *
  * MySQL has no out-of-band cancel packet (unlike PostgreSQL's `CancelRequest`). Instead, cancellation requires:
  *   1. Opening a **second** authenticated connection, the sidecar, outside the pool.
  *   2. Running `KILL QUERY <connectionId>` where `connectionId` is the server-assigned thread/connection ID (`HandshakeV10.threadId`) of
  *      the connection executing the query to be cancelled.
  *   3. The server interrupts the running query with ER_QUERY_INTERRUPTED (1317) / SQLSTATE `70100`.
  *   4. The sidecar is closed. It exists for one kill and is never pooled: a pooled sidecar would hold a server connection open for every
  *      cancel that ever fired, and the pool it would join is the one whose connection was just cancelled.
  *
  * Cancellation is best-effort: if the query completes before `KILL QUERY` is delivered, the kill is silently ignored.
  *
  * Reference: dev.mysql.com/doc/refman/8.0/en/kill.html
  */
private[kyo] object MysqlCancelExchange:

    /** Cancels the query running on `targetConnectionId` using the given `cancelConn`.
      *
      * The `cancelConn` is a separate, already-authenticated [[MysqlConnection]] opened for this kill alone. This method:
      *   1. Sends `KILL QUERY <targetConnectionId>` via the simple-query protocol.
      *   2. Reads the server response (OK = kill delivered; ERR = target not found or privilege error).
      *   3. Returns `Unit` on success, and also when the server answers ER_NO_SUCH_THREAD, which means the statement finished before the kill
      *      landed. That race is what best-effort cancellation is, so nothing is owed.
      *   4. Any other ERR raises the server's error.
      *
      * This method does not close `cancelConn`; its caller closes it on every exit edge, and never pools it. See
      * [[kyo.internal.mysql.MysqlSqlConnection.cancelInFlight]] for that finalizer.
      *
      * @param cancelConn
      *   the sidecar MySQL connection used as the cancel vehicle
      * @param targetConnectionId
      *   the `connectionId` from the target [[MysqlConnection]] (from [[MysqlConnection.connectionId]])
      */
    def kill(cancelConn: MysqlConnection, targetConnectionId: Long)(using Frame): Unit < (Async & Abort[SqlException]) =
        cancelConn.serverCapabilities.get.flatMap { caps =>
            val deprecateEof = (caps & kyo.internal.mysql.Capabilities.CLIENT_DEPRECATE_EOF) != 0L
            Abort.run[SqlException](
                SimpleQueryExchange.run(cancelConn.channel, s"KILL QUERY $targetConnectionId", deprecateEof, Maybe.Absent)
            ).flatMap {
                // OK response: the kill was delivered, or the query was already done and the server said OK anyway.
                case Result.Success(_) => ()
                // The thread is gone, so the statement this cancel was for has already ended. Reporting that as a
                // failed cancel would make the pool destroy a session the drain would have found reusable.
                case Result.Failure(e: SqlServerException) if noSuchThread(e) => ()
                case Result.Failure(e)                                        => Abort.fail(e)
                case Result.Panic(t)                                          => Abort.error(Result.Panic(t))
            }
        }
    end kill

    /** MySQL's ER_NO_SUCH_THREAD: the thread a `KILL` named is not on the server. */
    private val NoSuchThread = "1094"

    private def noSuchThread(e: SqlServerException): Boolean =
        e.extra.get("code").contains(NoSuchThread)

end MysqlCancelExchange
