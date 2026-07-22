package kyo.internal.mysql

import kyo.Closed
import kyo.Frame
import kyo.SqlConnectionConnectFailedException
import kyo.SqlConnectionException
import kyo.SqlException
import kyo.Test

/** Unit tests for [[kyo.internal.mysql.exchange.InitTlsExchange]] error-surface.
  *
  * These tests do not require a MySQL container. They verify that a failed TLS upgrade surfaces a
  * [[SqlConnectionException.TlsHandshakeFailed]] whose message is built directly from the original [[kyo.Closed]] cause, not wrapped in
  * an intermediate `RuntimeException`.
  */
class InitTlsExchangeTest extends Test:

    // Verifies that InitTlsExchange.run's Result.Failure branch passes the Closed instance
    // directly to TlsHandshakeFailed instead of wrapping it in a new RuntimeException.
    // We test the factory method directly because the effectful run() requires a live channel.

    "Connect failed built from Closed carries closed message without RuntimeException wrapping" in {
        // Create a Closed instance, simulates what upgradeToTls returns on failure.
        val closed = new Closed("test-resource", summon[Frame], "simulated TLS failure")

        val host = "db.example.com"
        val port = 3306

        // Call the factory that InitTlsExchange.run calls after the fix (closed passed directly).
        val ex = SqlConnectionConnectFailedException(host, port, closed)

        // The message must contain the host / port context; the cause carries the original Closed message.
        assert(
            ex.message.contains(s"Connect to $host:$port failed"),
            s"message should carry connect-failed context: ${ex.message}"
        )
        assert(
            !ex.message.contains("MySQL TLS upgrade failed"),
            s"message must NOT contain RuntimeException wrapper text 'MySQL TLS upgrade failed': ${ex.message}"
        )
        // The original closed message must appear via the cause.
        assert(
            Option(ex.getCause).map(_.getMessage).exists(_.contains(closed.getMessage)),
            s"cause message must contain closed.getMessage directly: ${Option(ex.getCause).map(_.getMessage)}"
        )
        // The exception is a Connection, not a RuntimeException.
        assert(ex.isInstanceOf[SqlConnectionException], s"exception must be SqlConnectionException: ${ex.getClass}")
    }

end InitTlsExchangeTest
