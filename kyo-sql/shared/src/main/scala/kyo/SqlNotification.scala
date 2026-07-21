package kyo
// TODO MOve to a companion
/** An asynchronous notification delivered by the PostgreSQL `LISTEN`/`NOTIFY` mechanism.
  *
  * The server sends a [[kyo.internal.postgres.NotificationResponse]] message asynchronously (between commands) to every connection that is
  * currently listening on the named channel. The [[kyo.internal.postgres.PostgresConnection]] routes those messages into its per-connection
  * [[kyo.Channel]] and this public type is the value exposed to callers via [[SqlClient.notifications]].
  *
  * @param channel
  *   the channel name that was used in `NOTIFY channel [, payload]`
  * @param payload
  *   the optional payload string (empty string when the `NOTIFY` had no payload clause)
  * @param processId
  *   the backend PID of the notifying session
  */
final case class SqlNotification(channel: String, payload: String, processId: Int) derives CanEqual
