package kyo.internal.postgres.exchange

import kyo.*
import kyo.SqlException
import kyo.internal.postgres.*

/** Sends the Postgres [[Terminate]] message and closes the underlying connection.
  *
  * The terminate message has no reply; the server closes the connection immediately upon receipt. We send it then close the connection from
  * our side to avoid waiting for TCP FIN.
  */
object TerminatorExchange:

    def run(channel: PostgresChannel)(using Frame): Unit < (Async & Abort[SqlException]) =
        val m = channel.marshallers.terminate
        channel.send(Terminate)(using m).andThen {
            kyo.Sync.Unsafe.defer(channel.conn.close())
        }
    end run

end TerminatorExchange
