package kyo.internal.mysql

import kyo.*
import kyo.Sql.*
import kyo.Test

/** Verifies the SQL [[MysqlDialect]] renders for an `INSERT`, including whether it appends a clause to report the generated key and
  * how it renders a multi-row insert.
  *
  * The [[kyo.SqlClient.InsertOutcome]] the execution path builds from that key is asserted separately in core, where the outcome type lives.
  */
class MysqlDialectInsertRenderTest extends Test:

    // Sql.BoundValue's existential value field cannot satisfy CanEqual derivation, so bind values are compared
    // after widening to Any.
    given CanEqual[Any, Any] = CanEqual.derived

    case class Account(id: Long, name: String)

    private def mySql(s: Executable[?]): String = s.render(MysqlDialect).onlySql.get

    // ── 1. MySQL renders no clause to report the generated key ─────────────────

    "MySQL INSERT for Account renders no RETURNING clause" in {
        val s = Sql.insert[Account].values(Account(0L, "Ada"))
        assert(mySql(s) == """INSERT INTO `account` (`id`, `name`) VALUES (?, ?)""")
    }

    // ── 2. Batch insert ───────────────────────────────────────────────────────
    //
    // A multi-row INSERT stays clause-free too, so nothing about the batch size introduces a key-reporting
    // clause this flavor does not have.

    "MySQL batch INSERT renders no RETURNING" in {
        val s = Sql.insert[Account].values(Account(0L, "Ada"), Account(0L, "Bob"), Account(0L, "Cal"))
        assert(
            mySql(s) == """INSERT INTO `account` (`id`, `name`) VALUES (?, ?), (?, ?), (?, ?)"""
        )
    }

    // ── 3. Overriding a column with its default ───────────────────────────────
    //
    // MySQL treats an explicit 0 in an `AUTO_INCREMENT` column as a request to generate under its default
    // `sql_mode`, so the insert that sends the row's own 0 happens to work here and fails on the other flavor.
    // `DEFAULT` asks for the key explicitly, which works on both, and is what these leaves pin.

    "MySQL INSERT with an overridden key sends DEFAULT in that cell and binds the rest" in {
        val r = Sql.insert[Account].values(Account(7L, "Ada")).overriding(_.id := Sql.default).render(MysqlDialect)
        assert(r.onlySql.get == """INSERT INTO `account` (`id`, `name`) VALUES (DEFAULT, ?)""")
        assert(r.params.size == 1, s"the row's own key must not travel, got ${r.params.size} binds")
        assert((r.params(0).value: Any) == "Ada")
    }

    "MySQL batch INSERT with an overridden key sends DEFAULT in every row" in {
        val s = Sql.insert[Account].values(Account(7L, "Ada"), Account(8L, "Bob")).overriding(_.id := Sql.default)
        assert(mySql(s) == """INSERT INTO `account` (`id`, `name`) VALUES (DEFAULT, ?), (DEFAULT, ?)""")
    }

end MysqlDialectInsertRenderTest
