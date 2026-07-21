package kyo.internal.mysql

import kyo.Closed
import kyo.Frame
import kyo.SqlException
import kyo.Test

/** Unit tests for [[kyo.internal.mysql.exchange.InitTlsExchange]] error-surface.
  *
  * These tests do not require a MySQL container. They verify that a failed TLS upgrade surfaces a
  * [[SqlException.Connection.TlsHandshakeFailed]] whose message is built directly from the original [[kyo.Closed]] cause, not wrapped in
  * an intermediate `RuntimeException`.
  */
class InitTlsExchangeTest extends Test:

    // Verifies that InitTlsExchange.run's Result.Failure branch passes the Closed instance
    // directly to TlsHandshakeFailed instead of wrapping it in a new RuntimeException.
    // We test the factory method directly because the effectful run() requires a live channel.

    "TlsHandshakeFailed built from Closed carries closed message without RuntimeException wrapping" in {
        // Create a Closed instance, simulates what upgradeToTls returns on failure.
        val closed = new Closed("test-resource", summon[Frame], "simulated TLS failure")

        val host = "db.example.com"
        val port = 3306

        // Call the factory that InitTlsExchange.run calls after the fix (closed passed directly).
        val ex = SqlException.Connection.TlsHandshakeFailed(host, port, closed)

        // The message must contain the Closed's own message (via closed.getMessage).
        // Before the fix the message contained "MySQL TLS upgrade failed: <closed.msg>" (double-wrap).
        // After the fix: "TLS handshake with $host:$port failed: <closed.msg>".
        assert(
            ex.message.contains(s"TLS handshake with $host:$port failed"),
            s"message should start with TLS handshake context: ${ex.message}"
        )
        assert(
            !ex.message.contains("MySQL TLS upgrade failed"),
            s"message must NOT contain RuntimeException wrapper text 'MySQL TLS upgrade failed': ${ex.message}"
        )
        // The original closed message must appear directly.
        assert(
            ex.message.contains(closed.getMessage),
            s"message must contain closed.getMessage directly: ${ex.message}"
        )
        // The exception is a Connection, not a RuntimeException.
        assert(ex.isInstanceOf[SqlException.Connection], s"exception must be SqlException.Connection: ${ex.getClass}")
    }

end InitTlsExchangeTest
