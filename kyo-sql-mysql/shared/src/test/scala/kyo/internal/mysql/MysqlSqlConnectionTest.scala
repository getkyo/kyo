package kyo.internal.mysql

import kyo.*
import kyo.internal.mysql.exchange.HandshakeExchange

/** Unit tests for the [[MysqlSqlConnection]] decisions a live server cannot be relied on to exercise.
  *
  * The `sslmode=allow` retry predicate is one of them: reaching its false arms needs a server that refuses a plaintext connection for a
  * reason other than `--require_secure_transport=ON`, which no fixture container is configured to do. The predicate is asserted directly
  * instead, over the exceptions the connection phase actually produces.
  */
class MysqlSqlConnectionTest extends kyo.Test:

    /** The exception a connection-phase `ErrPacket` becomes, which is what the retry predicate is handed. */
    private def connectFailure(errorCode: Int, sqlState: String, message: String): SqlException =
        HandshakeExchange.mkAuthError(ErrPacket(errorCode, sqlState, message))

    "ER_SECURE_TRANSPORT_REQUIRED is what sslmode=allow retries on" in {
        val e = connectFailure(
            3159,
            "HY000",
            "Connections using insecure transport are prohibited while --require_secure_transport=ON."
        )
        assert(MysqlSqlConnection.requiresSecureTransport(e), s"3159 must trigger the TLS retry, got $e")
    }

    // requiresSecureTransport must gate the TLS retry on the specific error code (3159), not on message text: a
    // plaintext connect failure that merely names secure transport must NOT be read as "the server wants TLS". Each
    // leaf below is a real connect failure that must not trigger the retry.

    "a too-many-connections failure is not a TLS retry" in {
        val e = connectFailure(1040, "08004", "Too many connections")
        assert(!MysqlSqlConnection.requiresSecureTransport(e), s"1040 must not trigger the TLS retry, got $e")
    }

    "an access-denied failure is not a TLS retry" in {
        val e = connectFailure(1045, "28000", "Access denied for user 'u'@'h' (using password: YES)")
        assert(!MysqlSqlConnection.requiresSecureTransport(e), s"1045 must not trigger the TLS retry, got $e")
    }

    "a failure whose text merely names secure transport is not a TLS retry" in {
        // The shape a message-text catch-all matches: the words are there, the error code is not.
        val e = connectFailure(1049, "42000", "Unknown database 'secure_transport_audit'")
        assert(
            !MysqlSqlConnection.requiresSecureTransport(e),
            s"only error 3159 says the server wants TLS, not the words in the message, got $e"
        )
    }

    "a transport failure carrying no server error code is not a TLS retry" in {
        // A SqlConnectionException has no error code at all, so there is nothing there to say the server wants TLS.
        val e = SqlConnectionClosedException("reading")
        assert(!MysqlSqlConnection.requiresSecureTransport(e), s"a closed socket must not trigger the TLS retry, got $e")
    }

end MysqlSqlConnectionTest
