package kyo

import kyo.Frame
import kyo.Maybe.Absent
import kyo.Maybe.Present
import kyo.SqlConnectionException
import kyo.SqlServerException
import kyo.SqlUnsupportedException
import kyo.Test

class SqlExceptionTest extends Test:

    "SqlServerException factory dispatches 23xxx to constraint violation" in {
        val ex = SqlServerException("23505", "ERROR", "duplicate key")
        assert(ex.isInstanceOf[SqlServerConstraintViolationException])
        assert(ex.isInstanceOf[SqlIntegrityViolation])
        val leaf = ex.asInstanceOf[SqlServerConstraintViolationException]
        assert(leaf.sqlState == "23505")
    }

    "SqlServerException factory dispatches 40001 to deadlock" in {
        val ex = SqlServerException("40001", "ERROR", "serialization failure")
        assert(ex.isInstanceOf[SqlServerDeadlockException])
        assert(ex.isInstanceOf[SqlRetryable])
    }

    "SqlServerException factory dispatches 40P01 to deadlock and carries SqlRetryable" in {
        val ex = SqlServerException("40P01", "ERROR", "deadlock detected")
        assert(ex.isInstanceOf[SqlServerDeadlockException])
        assert(ex.isInstanceOf[SqlRetryable])
        assert(ex.asInstanceOf[SqlServerDeadlockException].sqlState == "40P01")
    }

    "SqlServerException factory dispatches 42xxx to syntax" in {
        val ex = SqlServerException("42601", "ERROR", "syntax error")
        assert(ex.isInstanceOf[SqlServerSyntaxException])
    }

    "SqlServerException factory dispatches 08xxx to connection" in {
        val ex = SqlServerException("08006", "FATAL", "connection failure")
        assert(ex.isInstanceOf[SqlServerConnectionException])
    }

    "SqlServerException factory falls back to error for uncategorised sqlState" in {
        val ex = SqlServerException("99999", "ERROR", "unknown")
        assert(ex.isInstanceOf[SqlServerErrorException])
    }

    "SqlServerException carries the full field set through the factory" in {
        val ex = SqlServerException(
            sqlState = "23505",
            severity = "ERROR",
            message = "duplicate key value violates unique constraint",
            detail = Present("Key (id)=(42) already exists."),
            hint = Present("Change the id value."),
            position = Absent,
            extra = Map("table" -> "widgets", "constraint" -> "widgets_pkey"),
            sqlText = Present("INSERT INTO t VALUES ($1)"),
            paramCount = 1,
            connectionId = Present(99L)
        )
        assert(ex.isInstanceOf[SqlServerConstraintViolationException])
        val leaf = ex.asInstanceOf[SqlServerConstraintViolationException]
        assert(leaf.sqlState == "23505")
        assert(leaf.severity == "ERROR")
        assert(leaf.serverMessage == "duplicate key value violates unique constraint")
        assert(leaf.detail == Present("Key (id)=(42) already exists."))
        assert(leaf.hint == Present("Change the id value."))
        assert(leaf.position == Absent)
        assert(leaf.extra("table") == "widgets")
        assert(leaf.extra("constraint") == "widgets_pkey")
        assert(leaf.paramCount == 1)
        assert(leaf.connectionId == Present(99L))
        assert(ex.getMessage.contains("[23505] ERROR: duplicate key value violates unique constraint"))
        assert(ex.getMessage.contains("Detail: Key (id)=(42) already exists."))
        assert(ex.getMessage.contains("Hint: Change the id value."))
        assert(ex.getMessage.contains("SQL: INSERT INTO t VALUES ($1)"))
        assert(ex.getMessage.contains("Params: 1"))
        assert(ex.getMessage.contains("ConnectionId: 99"))
    }

    "SqlConnectionPoolClosedException is a SqlConnectionException and a SqlException" in {
        val ex: SqlException = SqlConnectionPoolClosedException()
        assert(ex.isInstanceOf[SqlConnectionException])
        assert(ex.getMessage.contains("pool is closed"))
    }

    "SqlConnectionAcquireTimeoutException carries the acquire timeout and is retryable" in {
        val ex: SqlException = SqlConnectionAcquireTimeoutException(5.seconds)
        assert(ex.isInstanceOf[SqlRetryable])
        assert(ex.getMessage.contains("5"))
    }

    "SqlConnectionUrlParseException carries the raw URL and scheme" in {
        val ex = SqlConnectionUrlParseException("bogus://x", "bogus")
        assert(ex.rawUrl == "bogus://x")
        assert(ex.scheme == "bogus")
        assert(ex.getMessage.contains("bogus"))
    }

    "SqlRequestMysqlTxRequiresConnectionApiException carries operation name" in {
        val ex = SqlRequestMysqlTxRequiresConnectionApiException("query")
        assert(ex.operation == "query")
        assert(ex.getMessage.contains("query"))
    }

    "SqlDecodeColumnNullException reports column index" in {
        val ex = SqlDecodeColumnNullException(3)
        assert(ex.columnIndex == 3)
        assert(ex.getMessage.contains("3"))
    }

    "SqlDecodeColumnNullException(columnName) omits the -1 index from the rendered message" in {
        val ex = SqlDecodeColumnNullException("email")
        assert(ex.columnIndex == -1)
        assert(ex.columnName == Present("email"))
        assert(ex.getMessage.contains("Non-nullable column 'email' was SQL NULL"))
        assert(!ex.getMessage.contains("index -1"))
    }

    "SqlConnectionAuthenticationFailedException carries the SqlAuthenticationFailure marker" in {
        val ex: SqlException = SqlConnectionAuthenticationFailedException("28P01", 0, "invalid password")
        assert(ex.isInstanceOf[SqlAuthenticationFailure])
        assert(ex.isInstanceOf[SqlConnectionException])
    }

    "SqlConnectionConnectFailedException propagates the underlying cause" in {
        val cause                 = new RuntimeException("boom")
        val ex                    = SqlConnectionConnectFailedException("db.example.com", 5432, cause)
        val propagated: Throwable = ex.getCause
        assert(propagated eq cause)
    }

    "SqlDecodeColumnNotFoundException reports column name" in {
        val ex = SqlDecodeColumnNotFoundException("id")
        assert(ex.columnName == "id")
        assert(ex.getMessage.contains("id"))
    }

    "SqlUnsupportedReturningOnMysqlException is a SqlUnsupportedException" in {
        val ex: SqlException = SqlUnsupportedReturningOnMysqlException()
        assert(ex.isInstanceOf[SqlUnsupportedException])
        assert(ex.getMessage.contains("RETURNING"))
    }

    "every leaf is a KyoException" in {
        val conn: SqlException = SqlConnectionPoolClosedException()
        val req: SqlException  = SqlRequestMysqlTxRequiresConnectionApiException("execute")
        val srv: SqlException  = SqlServerException("00000", "ERROR", "s")
        val dec: SqlException  = SqlDecodeColumnNullException(0)
        val uns: SqlException  = SqlUnsupportedReturningOnMysqlException()
        assert(conn.isInstanceOf[KyoException])
        assert(req.isInstanceOf[KyoException])
        assert(srv.isInstanceOf[KyoException])
        assert(dec.isInstanceOf[KyoException])
        assert(uns.isInstanceOf[KyoException])
    }

    "Render[SqlException] renders a SqlConnectionException to a human-readable string" in {
        val ex       = SqlConnectionPoolClosedException()
        val rendered = Render[SqlException].asString(ex)
        assert(rendered.contains("pool is closed"))
    }

end SqlExceptionTest
