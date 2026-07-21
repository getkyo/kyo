package kyo

import kyo.*
import kyo.net.NetTlsConfig
// TODO move to a companion
/** An opaque handle that can be used to cancel the query running on a specific connection.
  *
  * Obtained from [[SqlClient.cancellableQuery]]. Pass the handle to [[SqlClient.cancel]] from a separate fiber while the query fiber is
  * still active to request cancellation.
  *
  * There are two concrete variants:
  *   - [[CancelHandle.Postgres]], PostgreSQL cancel via a fresh `CancelRequest` TCP connection (no auth required).
  *   - [[CancelHandle.Mysql]], MySQL cancel via `KILL QUERY <connectionId>` on a pooled connection.
  *
  * Both variants carry the `address` field for network connectivity.
  */
sealed abstract class SqlCancelHandle:
    /** Server address (host, port, db, user). */
    def address: SqlConfig.Address

object SqlCancelHandle:

    /** PostgreSQL cancel handle.
      *
      * Carries the `(processId, secretKey)` pair from `BackendKeyData` and the TLS configuration. To cancel, open a fresh TCP connection
      * (never from the pool), send a 16-byte `CancelRequest`, and close. The database will abort the query identified by this pair, causing
      * the query fiber to receive a [[SqlException.Server]] with SQLSTATE `57014`.
      *
      * @param address
      *   server address
      * @param tls
      *   TLS configuration used by the original connection (if any); the cancel connection will try the same upgrade
      * @param processId
      *   backend PID from `BackendKeyData`
      * @param secretKey
      *   secret key from `BackendKeyData`
      */
    final case class Postgres(
        address: SqlConfig.Address,
        tls: Maybe[NetTlsConfig],
        processId: Int,
        secretKey: Int
    ) extends SqlCancelHandle
        derives CanEqual

    /** MySQL cancel handle.
      *
      * Carries the server-assigned `connectionId` (thread ID) of the connection executing the query. To cancel, acquire a second
      * authenticated connection from the pool (with a bounded timeout), send `KILL QUERY <connectionId>`, and release the connection. The
      * database will interrupt the running query with ER_QUERY_INTERRUPTED / SQLSTATE `70100`.
      *
      * @param address
      *   server address (used to acquire the cancel connection from the pool)
      * @param connectionId
      *   the target connection's MySQL thread/connection ID (from `HandshakeV10.threadId`)
      */
    final case class Mysql(
        address: SqlConfig.Address,
        connectionId: Long
    ) extends SqlCancelHandle
        derives CanEqual

end SqlCancelHandle
