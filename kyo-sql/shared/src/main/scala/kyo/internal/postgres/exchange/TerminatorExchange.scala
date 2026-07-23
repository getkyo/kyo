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
  * fiber interrupt). `Sync.ensure` alone was not enough: on the interrupt path the fiber can be torn down before the ensure evaluates,
  * leaving the FD open past end-of-run and tripping the leak check on Linux CI.
  */
object TerminatorExchange:

    def run(channel: PostgresChannel)(using Frame): Unit < (Async & Abort[SqlException]) =
        val m = channel.marshallers.terminate
        Scope.run {
            Scope.ensure(kyo.Sync.Unsafe.defer(channel.conn.close())).andThen {
                channel.send(Terminate)(using m)
            }
        }
    end run

end TerminatorExchange
