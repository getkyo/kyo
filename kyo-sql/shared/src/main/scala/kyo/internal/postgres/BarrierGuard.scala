package kyo.internal.postgres

import kyo.*
import kyo.Log
import kyo.SqlException

/** Wraps a protocol command body with a drain-to-[[ReadyForQuery]] guarantee on any exit path.
  *
  * The PostgreSQL simple-query protocol always terminates with a [[ReadyForQuery]] message. If the command body fails (e.g. a server error
  * response is raised as [[SqlException.Server]]), the server still sends [[ReadyForQuery]] before it is ready for the next command. If we
  * skip draining that message the connection is left in a corrupt state: the next command will read stale bytes.
  *
  * [[runWithBarrier]] captures the outcome of `body`, drains all inbound messages to [[ReadyForQuery]], then either returns the value or
  * re-raises the original error. This mirrors the `thenWaitFor(ReadyForQuery)` discipline from ndbc.
  *
  * Note: [[SimpleQueryExchange]] reads [[ReadyForQuery]] itself as part of the protocol, so it does not use [[runWithBarrier]]. This helper
  * is intended for the Extended Protocol where the client sends `Sync` and the server always ends with a [[ReadyForQuery]].
  */
object BarrierGuard:

    /** Executes `body` and always drains inbound messages to [[ReadyForQuery]] before returning or re-raising.
      *
      * If `body` completes normally the drain still runs (consuming the ReadyForQuery that ends the command). If `body` aborts with an
      * error the drain also runs, leaving the connection in a clean idle state before the error is propagated.
      */
    def runWithBarrier[A](channel: PostgresChannel, body: => A < (Async & Abort[SqlException]))(using
        Frame
    ): A < (Async & Abort[SqlException]) =
        Abort.run[SqlException](body).flatMap { result =>
            drainToReadyForQuery(channel).flatMap { _ =>
                result match
                    case Result.Success(a) => a
                    case Result.Failure(e) => Abort.fail(e)
                    case Result.Panic(t) =>
                        Log.error(s"[kyo-sql] BarrierGuard: re-raising panic: ${t.getMessage}").andThen(
                            Abort.panic(t)
                        )
            }
        }

    /** Drains the inbound channel, discarding all messages, until a [[ReadyForQuery]] is seen.
      *
      * Any error during draining is logged and swallowed — we must not mask the original error from `body`.
      */
    private def drainToReadyForQuery(channel: PostgresChannel)(using Frame): Unit < Async =
        Abort.run[SqlException](drainLoop(channel)).flatMap {
            case Result.Success(_) => ()
            case Result.Failure(e) =>
                // Log recoverable error during drain but do not re-raise; the connection is likely dead.
                Log.warn(s"kyo.sql: error draining to ReadyForQuery error=${e.getMessage}")
            case Result.Panic(e) =>
                Log.error(s"[kyo-sql] BarrierGuard: panic during drain: ${e.getMessage}")
        }

    private def drainLoop(channel: PostgresChannel)(using Frame): Unit < (Async & Abort[SqlException]) =
        channel.receive.map {
            case _: ReadyForQuery => ()
            case _                => drainLoop(channel)
        }

end BarrierGuard
