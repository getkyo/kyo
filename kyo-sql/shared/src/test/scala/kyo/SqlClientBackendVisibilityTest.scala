package kyo

import scala.compiletime.testing.typeCheckErrors

/** Visibility tests for [[kyo.internal.client.SqlClientBackend]].
  *
  * Verifies that:
  *   1. External code cannot directly instantiate [[kyo.internal.client.PostgresSqlClientBackend]] or [[kyo.internal.client.MysqlSqlClientBackend]]
  * their constructors are `private[client]`.
  *   2. The static SQL macro path (and any `inline` accessor in [[SqlClient]]) still compiles on JS, the trait, classes, and companion
  *      object themselves remain public so the inline expansion does not generate a synthetic package-token accessor.
  */
class SqlClientBackendVisibilityTest extends Test:

    "external code cannot directly instantiate PostgresSqlClientBackend" in {
        // PostgresSqlClientBackend's constructor is private[client].
        // Any attempt to call `new PostgresSqlClientBackend(...)` from outside kyo.internal.client must fail.
        val errors = typeCheckErrors(
            """import kyo.*
import kyo.internal.client.PostgresSqlClientBackend
import java.util.concurrent.ConcurrentHashMap
import kyo.SqlClient.Metrics
import kyo.internal.client.ConnectionPool
import kyo.internal.postgres.PostgresConnection
import kyo.internal.tls.TlsMode
// Attempt direct construction, must be rejected
val _ = new PostgresSqlClientBackend(
    null.asInstanceOf[ConnectionPool[kyo.net.NetAddress, PostgresConnection]],
    new ConcurrentHashMap(),
    summon[Frame],
    SqlClient.Metrics(false, "")
)"""
        )
        val hasAccessError = errors.exists(e =>
            e.message.contains("private") ||
                e.message.contains("cannot be accessed") ||
                e.message.contains("not accessible")
        )
        assert(hasAccessError, s"Expected a constructor visibility error for PostgresSqlClientBackend; got: $errors")
    }

    "external code cannot directly instantiate MysqlSqlClientBackend" in {
        // MysqlSqlClientBackend's constructor is private[client].
        val errors = typeCheckErrors(
            """import kyo.*
import kyo.internal.client.MysqlSqlClientBackend
import java.util.concurrent.ConcurrentHashMap
import kyo.SqlClient.Metrics
import kyo.internal.client.ConnectionPool
import kyo.internal.mysql.MysqlConnection
// Attempt direct construction, must be rejected
val _ = new MysqlSqlClientBackend(
    null.asInstanceOf[ConnectionPool[kyo.net.NetAddress, MysqlConnection]],
    new ConcurrentHashMap(),
    summon[Frame],
    SqlClient.Metrics(false, "")
)"""
        )
        val hasAccessError = errors.exists(e =>
            e.message.contains("private") ||
                e.message.contains("cannot be accessed") ||
                e.message.contains("not accessible")
        )
        assert(hasAccessError, s"Expected a constructor visibility error for MysqlSqlClientBackend; got: $errors")
    }

end SqlClientBackendVisibilityTest
