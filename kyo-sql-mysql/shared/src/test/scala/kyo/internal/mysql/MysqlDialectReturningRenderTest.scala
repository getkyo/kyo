package kyo.internal.mysql

import kyo.*
import kyo.Sql.*
import kyo.SqlUnsupportedException
import kyo.Test

/** Verifies how [[MysqlDialect]] answers for a user-specified `RETURNING` clause on `INSERT`, `UPDATE`, and `DELETE`.
  */
class MysqlDialectReturningRenderTest extends Test:

    case class Event(id: Long, name: String, createdAt: String)

    given CanEqual[Any, Any] = CanEqual.derived

    // RETURNING on MySQL raises SqlUnsupportedException (requires Frame for typed error).
    "RETURNING on MySQL raises Unsupported" in {
        val s = Sql.insert[Event].values(Event(0L, "boot", "2024-01-01")).returning(_.id)
        val ex = intercept[SqlUnsupportedException] {
            s.render(MysqlDialect)
        }
        assert(ex.getMessage.contains("RETURNING"))
    }

end MysqlDialectReturningRenderTest
