package kyo.internal.mysql.exchange

import kyo.*
import kyo.SqlException
import kyo.internal.mysql.*

/** Cancels a running MySQL query by issuing `KILL QUERY <connectionId>` on a separate connection.
  *
  * MySQL has no out-of-band cancel packet (unlike PostgreSQL's `CancelRequest`). Instead, cancellation requires:
  *   1. Acquiring a **second** authenticated connection.
  *   2. Running `KILL QUERY <connectionId>` where `connectionId` is the server-assigned thread/connection ID (`HandshakeV10.threadId`) of
  *      the connection executing the query to be cancelled.
  *   3. The server interrupts the running query with ER_QUERY_INTERRUPTED (1317) / SQLSTATE `70100`.
  *   4. The second connection is returned to the pool (it remains healthy).
  *
  * Cancellation is best-effort: if the query completes before `KILL QUERY` is delivered, the kill is silently ignored.
  *
  * Reference: dev.mysql.com/doc/refman/8.0/en/kill.html
  */
private[kyo] object MysqlCancelExchange:

    /** Cancels the query running on `targetConnectionId` using the given `cancelConn`.
      *
      * The `cancelConn` is a separate, already-authenticated [[MysqlConnection]] obtained from the pool or created ad-hoc. This method:
      *   1. Sends `KILL QUERY <targetConnectionId>` via the simple-query protocol.
      *   2. Reads the server response (OK = kill delivered; ERR = target not found or privilege error).
      *   3. Returns `Unit` on success. If the target is not found (ER_NO_SUCH_THREAD), returns `Unit` silently (query already done).
      *   4. Any other ERR raises [[SqlException.Connection]].
      *
      * The caller is responsible for releasing `cancelConn` back to the pool after this method returns.
      *
      * @param cancelConn
      *   a healthy MySQL connection used as the cancel vehicle
      * @param targetConnectionId
      *   the `connectionId` from the target [[MysqlConnection]] (from [[MysqlConnection.connectionId]])
      */
    def kill(cancelConn: MysqlConnection, targetConnectionId: Long)(using Frame): Unit < (Async & Abort[SqlException]) =
        cancelConn.serverCapabilities.get.flatMap { caps =>
            val deprecateEof = (caps & kyo.internal.mysql.Capabilities.CLIENT_DEPRECATE_EOF) != 0L
            SimpleQueryExchange.run(cancelConn.channel, s"KILL QUERY $targetConnectionId", deprecateEof, Maybe.Absent).flatMap {
                case (_, _) =>
                    // OK response, kill delivered (or query already done and server said OK anyway).
                    ()
            }
        }
    end kill

end MysqlCancelExchange
