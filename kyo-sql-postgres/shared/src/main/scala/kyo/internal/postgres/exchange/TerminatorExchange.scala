package kyo.internal.postgres.exchange

import kyo.*
import kyo.SqlException
import kyo.internal.postgres.*

/** Sends the Postgres [[Terminate]] message and closes the underlying connection.
  *
  * The terminate message has no reply; the server closes the connection immediately upon receipt. We send it then close the connection from
  * our side to avoid waiting for TCP FIN.
  *
  * `channel.conn.close()` runs as a `Scope.ensure` finalizer so the socket closes on every completion path (success, failure, abort,
  * fiber interrupt). It uses a `Scope.ensure` rather than a `Sync.ensure` because on the interrupt path the fiber can be torn down before a
  * `Sync.ensure` evaluates, which would leave the FD open past end-of-run.
  */
object TerminatorExchange:

    def run(channel: PostgresChannel)(using Frame): Unit < (Async & Abort[SqlException]) =
        val m = channel.marshallers.terminate
        Scope.run {
            // Unsafe: kyo-net Connection.close is unsafe-tier; registered as the scope's last-resort close.
            Scope.ensure(kyo.Sync.Unsafe.defer(channel.conn.close())).andThen {
                channel.send(Terminate)(using m)
            }
        }
    end run

end TerminatorExchange
