package kyo.internal.postgres.exchange

import kyo.*
import kyo.SqlException
import kyo.internal.postgres.*

/** Sends the Postgres [[Terminate]] message and closes the underlying connection.
  *
  * The terminate message has no reply; the server closes the connection immediately upon receipt. We send it then close the connection from
  * our side to avoid waiting for TCP FIN.
  *
  * `channel.conn.close()` fires in a `Sync.ensure` so the socket closes even when `send(Terminate)` aborts (e.g., server already dropped the
  * connection, TLS bookkeeping error, fiber interrupt). Without the ensure the socket would leak on any send-failure path, and the
  * end-of-run FD leak check on Linux CI would trip.
  */
object TerminatorExchange:

    def run(channel: PostgresChannel)(using Frame): Unit < (Async & Abort[SqlException]) =
        val m = channel.marshallers.terminate
        kyo.Sync.ensure(kyo.Sync.Unsafe.defer(channel.conn.close())) {
            channel.send(Terminate)(using m)
        }
    end run

end TerminatorExchange
