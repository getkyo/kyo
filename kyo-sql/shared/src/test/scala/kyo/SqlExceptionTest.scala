package kyo

import kyo.Frame
import kyo.Maybe.Absent
import kyo.Maybe.Present
import kyo.Test

class SqlExceptionTest extends Test:

    "SqlException.Server carries sqlState" in {
        val ex = SqlException.Server("23505", "ERROR", "duplicate key")
        assert(ex.sqlState == "23505")
    }

    "SqlException.Server decodes full PG ErrorResponse" in {
        val ex = SqlException.Server(
            sqlState = "23505",
            severity = "ERROR",
            message = "duplicate key",
            detail = Present("Key (id)=(1) already exists."),
            hint = Absent,
            position = Absent,
            extra = Map("table" -> "widgets", "constraint" -> "widgets_pkey"),
            sqlText = Absent,
            paramCount = 0,
            connectionId = Absent,
            frame = summon[Frame]
        )
        assert(ex.sqlState == "23505")
        assert(ex.severity == "ERROR")
        assert(ex.message == "duplicate key")
        assert(ex.detail == Present("Key (id)=(1) already exists."))
        assert(ex.hint == Absent)
        assert(ex.position == Absent)
        assert(ex.extra("table") == "widgets")
        assert(ex.extra("constraint") == "widgets_pkey")
    }

    "SqlException.Connection is a SqlException" in {
        val ex: SqlException = SqlException.Connection("pool exhausted", summon[Frame])
        assert(ex.getMessage.contains("pool exhausted"))
    }

    "SqlException.Request is a SqlException" in {
        val ex: SqlException = SqlException.Request("missing encoder", Absent, summon[Frame])
        assert(ex.getMessage.contains("missing encoder"))
    }

    "SqlException.Decode is a SqlException" in {
        val ex: SqlException = SqlException.Decode("failed to decode column 'id'", Absent, summon[Frame])
        assert(ex.getMessage.contains("failed to decode column 'id'"))
    }

    "SqlException.Connection message contains the pre-change concatenated string" in {
        val ex       = SqlException.Connection("pool exhausted", summon[Frame])
        val expected = "pool exhausted"
        assert(ex.getMessage.contains(expected))
    }

    "SqlException.Request without sqlText message contains the pre-change concatenated string" in {
        val ex       = SqlException.Request("missing encoder for type Foo", Absent, summon[Frame])
        val expected = "missing encoder for type Foo"
        assert(ex.getMessage.contains(expected))
    }

    "SqlException.Request with sqlText message contains the pre-change concatenated string" in {
        val ex = SqlException.Request("missing encoder for type Foo", Present("SELECT 1"), summon[Frame])
        // primary message part
        assert(ex.getMessage.contains("missing encoder for type Foo"))
        // SQL annotation part (pre-change: "\n  SQL: SELECT 1")
        assert(ex.getMessage.contains("SQL: SELECT 1"))
    }

    "SqlException.Server full-field message contains the pre-change concatenated string" in {
        val ex = SqlException.Server(
            sqlState = "23505",
            severity = "ERROR",
            message = "duplicate key value violates unique constraint",
            detail = Present("Key (id)=(42) already exists."),
            hint = Present("Change the id value."),
            position = Absent,
            extra = Map.empty,
            sqlText = Present("INSERT INTO t VALUES ($1)"),
            paramCount = 1,
            connectionId = Present(99L),
            frame = summon[Frame]
        )
        // Each component of the pre-change concatenated string verified as a literal:
        assert(ex.getMessage.contains("[23505] ERROR: duplicate key value violates unique constraint"))
        assert(ex.getMessage.contains("Detail: Key (id)=(42) already exists."))
        assert(ex.getMessage.contains("Hint: Change the id value."))
        assert(ex.getMessage.contains("SQL: INSERT INTO t VALUES ($1)"))
        assert(ex.getMessage.contains("Params: 1"))
        assert(ex.getMessage.contains("ConnectionId: 99"))
    }

    "SqlException.Decode message contains the pre-change concatenated string" in {
        val ex = SqlException.Decode("cannot decode NULL as Int", Present("SELECT id FROM t"), summon[Frame])
        // primary message part
        assert(ex.getMessage.contains("cannot decode NULL as Int"))
        // SQL annotation part (pre-change: "\n  SQL: SELECT id FROM t")
        assert(ex.getMessage.contains("SQL: SELECT id FROM t"))
    }

    "SqlException.Unsupported has the correct message and is a KyoException" in {
        val ex = SqlException.Unsupported("op x", summon[Frame])
        assert(ex.getMessage.contains("op x"))
        assert(ex.isInstanceOf[KyoException])
    }

    "SqlException and every variant is an instanceof KyoException" in {
        val conn: SqlException = SqlException.Connection("c", summon[Frame])
        val req: SqlException  = SqlException.Request("r", Absent, summon[Frame])
        val srv: SqlException  = SqlException.Server("00000", "ERROR", "s")(using summon[Frame])
        val dec: SqlException  = SqlException.Decode("d", Absent, summon[Frame])
        val uns: SqlException  = SqlException.Unsupported("u", summon[Frame])
        assert(conn.isInstanceOf[KyoException])
        assert(req.isInstanceOf[KyoException])
        assert(srv.isInstanceOf[KyoException])
        assert(dec.isInstanceOf[KyoException])
        assert(uns.isInstanceOf[KyoException])
    }

    "Render[SqlException] renders SqlException.Connection to human-readable string" in {
        val ex       = SqlException.Connection("test connection error", summon[Frame])
        val rendered = Render[SqlException].asString(ex)
        assert(rendered.contains("test connection error"))
    }

    "two structurally-equal SqlException.Decode values compare ==" in {
        val frame = summon[Frame]
        val e1    = SqlException.Decode("cannot decode NULL as Int", Maybe.Absent, frame)
        val e2    = SqlException.Decode("cannot decode NULL as Int", Maybe.Absent, frame)
        assert(e1 == e2)
    }

end SqlExceptionTest
