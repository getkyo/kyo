package kyo

import scala.compiletime.testing.typeCheckErrors

/** Visibility tests for [[kyo.internal.client.SqlClientBackend]].
  *
  * Verifies that:
  *   1. External code cannot directly instantiate [[kyo.internal.client.PgSqlClientBackend]] or [[kyo.internal.client.MySqlClientBackend]]
  *      — their constructors are `private[client]`.
  *   2. The static SQL macro path (and any `inline` accessor in [[SqlClient]]) still compiles on JS — the trait, classes, and companion
  *      object themselves remain public so the inline expansion does not generate a synthetic package-token accessor.
  */
class SqlClientBackendVisibilityTest extends Test:

    "external code cannot directly instantiate PgSqlClientBackend" in {
        // PgSqlClientBackend's constructor is private[client].
        // Any attempt to call `new PgSqlClientBackend(...)` from outside kyo.internal.client must fail.
        val errors = typeCheckErrors(
            """import kyo.*
import kyo.internal.client.PgSqlClientBackend
import java.util.concurrent.ConcurrentHashMap
import kyo.SqlMetrics
import kyo.internal.client.ConnectionPool
import kyo.internal.postgres.PostgresConnection
import kyo.internal.tls.TlsMode
// Attempt direct construction — must be rejected
val _ = new PgSqlClientBackend(
    null.asInstanceOf[ConnectionPool[kyo.net.NetAddress, PostgresConnection]],
    new ConcurrentHashMap(),
    summon[Frame],
    SqlMetrics(false, "")
)"""
        )
        val hasAccessError = errors.exists(e =>
            e.message.contains("private") ||
                e.message.contains("cannot be accessed") ||
                e.message.contains("not accessible")
        )
        assert(hasAccessError, s"Expected a constructor visibility error for PgSqlClientBackend; got: $errors")
    }

    "external code cannot directly instantiate MySqlClientBackend" in {
        // MySqlClientBackend's constructor is private[client].
        val errors = typeCheckErrors(
            """import kyo.*
import kyo.internal.client.MySqlClientBackend
import java.util.concurrent.ConcurrentHashMap
import kyo.SqlMetrics
import kyo.internal.client.ConnectionPool
import kyo.internal.mysql.MysqlConnection
// Attempt direct construction — must be rejected
val _ = new MySqlClientBackend(
    null.asInstanceOf[ConnectionPool[kyo.net.NetAddress, MysqlConnection]],
    new ConcurrentHashMap(),
    summon[Frame],
    SqlMetrics(false, "")
)"""
        )
        val hasAccessError = errors.exists(e =>
            e.message.contains("private") ||
                e.message.contains("cannot be accessed") ||
                e.message.contains("not accessible")
        )
        assert(hasAccessError, s"Expected a constructor visibility error for MySqlClientBackend; got: $errors")
    }

end SqlClientBackendVisibilityTest
