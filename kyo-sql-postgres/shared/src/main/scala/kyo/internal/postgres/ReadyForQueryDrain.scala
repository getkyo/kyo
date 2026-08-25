package kyo.internal.postgres

import kyo.*
import kyo.SqlException

/** Reads forward to the next [[ReadyForQuery]], dispatching the asynchronous messages that arrive on the way.
  *
  * PostgreSQL ends every command cycle with a `ReadyForQuery`, so a caller that stopped short of it would leave bytes on the wire for the
  * next exchange to read as its own. Every exchange therefore has a point where it reads forward to that message. This is that point, once,
  * so what happens to a message seen mid-drain is one decision rather than one per exchange.
  *
  * `ParameterStatus` and `NotificationResponse` are dispatched, not discarded. `ParameterStatus` is how the server reports a session
  * variable's new value, and a `SET` inside a statement that then fails is the ordinary way to reach one during an error drain; dropping it
  * would leave [[PostgresConnection.parameters]] reporting a value the session no longer holds, for the rest of that connection's life.
  * `NotificationResponse` is a payload a `LISTEN`er is waiting for and the server never sends it again. Everything else is discarded, which
  * is right for the two kinds that reach here: `NoticeResponse`, the server's own commentary on a command that is already over, and the
  * remains of the response the caller abandoned.
  *
  * The [[ReadyForQuery]] is returned rather than consumed silently, because its status byte is the server's own account of whether a
  * transaction is open and a caller that tracks that needs it.
  */
private[postgres] object ReadyForQueryDrain:

    def run(
        channel: PostgresChannel,
        onParameterStatus: (String, String) => Unit < Async,
        onNotification: NotificationResponse => Unit < Async
    )(using Frame): ReadyForQuery < (Async & Abort[SqlException]) =
        channel.receive.flatMap {
            case rfq: ReadyForQuery      => rfq
            case ParameterStatus(n, v)   => onParameterStatus(n, v).andThen(run(channel, onParameterStatus, onNotification))
            case n: NotificationResponse => onNotification(n).andThen(run(channel, onParameterStatus, onNotification))
            case _                       => run(channel, onParameterStatus, onNotification)
        }

end ReadyForQueryDrain
