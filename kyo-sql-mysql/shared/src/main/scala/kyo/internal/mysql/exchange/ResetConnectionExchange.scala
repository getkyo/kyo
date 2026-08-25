package kyo.internal.mysql.exchange

import kyo.*
import kyo.SqlConnectionResetFailedException
import kyo.SqlConnectionUnexpectedMessageException
import kyo.SqlException
import kyo.internal.mysql.*

/** Sends [[ComResetConnection]] (0x1F) to the server and reads the [[OkPacket]] response.
  *
  * `COM_RESET_CONNECTION` clears all per-connection session state (user variables, prepared statements, open transactions, current schema,
  * advisory locks, last-insert-id) without re-running the authentication handshake. It is the cheapest way to return a connection to a
  * neutral state after use.
  *
  * Reference: MySQL Internals, `COM_RESET_CONNECTION`
  */
private[mysql] object ResetConnectionExchange:

    /** Sends `COM_RESET_CONNECTION` and waits for the server's `OK` response.
      *
      * @param channel
      *   the active MySQL channel (connection must not be inside a transaction)
      * @return
      *   `Unit` on success; raises a [[kyo.SqlConnectionException]] leaf if the server replies with an [[ErrPacket]] or an unexpected message
      */
    def run(channel: MysqlChannel)(using Frame): Unit < (Async & Abort[SqlException]) =
        channel.resetSeq()
        channel.send(ComResetConnection)(using channel.marshallers.comResetConnection).flatMap { _ =>
            channel.receive(false).flatMap {
                case _: OkPacket =>
                    ()
                case err: ErrPacket =>
                    Abort.fail(SqlConnectionResetFailedException(err.errorCode, err.errorMessage))
                case other =>
                    Abort.fail(SqlConnectionUnexpectedMessageException("COM_RESET_CONNECTION", "OkPacket / ErrPacket", other.toString))
            }
        }
    end run

end ResetConnectionExchange
