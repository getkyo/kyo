package kyo.internal.postgres

import kyo.*
import kyo.Sql.*
import kyo.Test

/** Verifies the SQL [[PostgresDialect]] renders for an `INSERT`, including whether it appends a clause to report the generated key and
  * how it renders a multi-row insert.
  *
  * The [[kyo.SqlClient.InsertOutcome]] the execution path builds from that key is asserted separately in core, where the outcome type lives.
  */
class PostgresDialectInsertRenderTest extends Test:

    // Sql.BoundValue's existential value field cannot satisfy CanEqual derivation, so bind values are compared
    // after widening to Any.
    given CanEqual[Any, Any] = CanEqual.derived

    case class Account(id: Long, name: String)
    case class Tag(name: String)
    case class Stats(views: Int, label: String)

    private def pgSql(s: Executable[?]): String = s.render(PostgresDialect).onlySql.get

    // ── 1. PG renders RETURNING "id" for Long-headed case class ─────────────────

    "PG INSERT for Account auto-renders RETURNING \"id\"" in {
        val s = Sql.insert[Account].values(Account(0L, "Ada"))
        assert(pgSql(s) == """INSERT INTO "account" ("id", "name") VALUES ($1, $2) RETURNING "id"""")
    }

    // ── 2. Schema fallback: no Long head → no RETURNING ────────────────────────

    "PG INSERT for Tag (no Long head) renders no RETURNING" in {
        val s = Sql.insert[Tag].values(Tag("urgent"))
        assert(pgSql(s) == """INSERT INTO "tag" ("name") VALUES ($1)""")
    }

    // Negative form: Int head also falls back (only Long triggers the auto-key path).

    "PG INSERT for case class with Int head renders no RETURNING" in {
        val s = Sql.insert[Stats].values(Stats(42, "hot"))
        assert(pgSql(s) == """INSERT INTO "stats" ("views", "label") VALUES ($1, $2)""")
    }

    // ── 3. Batch insert ───────────────────────────────────────────────────────
    //
    // A multi-row INSERT keeps the single trailing RETURNING clause, so the statement reports one key
    // for the batch rather than one per row.

    "PG batch INSERT (values(v1, v2, v3)) ends with RETURNING \"id\"" in {
        val s = Sql.insert[Account].values(Account(0L, "Ada"), Account(0L, "Bob"), Account(0L, "Cal"))
        assert(
            pgSql(s) == """INSERT INTO "account" ("id", "name") VALUES ($1, $2), ($3, $4), ($5, $6) RETURNING "id""""
        )
    }

    // ── 4. Overriding a column with its default ───────────────────────────────
    //
    // The generated-key insert. PostgreSQL accepts an explicit value in a `BIGSERIAL` column without advancing
    // the sequence, so an insert that sends the row's own 0 there lands a 0, reports 0 back through
    // RETURNING, and collides with itself on the next insert. `DEFAULT` in that cell is what asks the server for
    // the key, and these leaves assert the keyword reaches the statement and the row's value does not.

    "PG INSERT with an overridden key sends DEFAULT in that cell and binds the rest" in {
        val r = Sql.insert[Account].values(Account(7L, "Ada")).overriding(_.id := Sql.default).render(PostgresDialect)
        assert(r.onlySql.get == """INSERT INTO "account" ("id", "name") VALUES (DEFAULT, $1) RETURNING "id"""")
        assert(r.params.size == 1, s"the row's own key must not travel, got ${r.params.size} binds")
        assert((r.params(0).value: Any) == "Ada")
    }

    "PG batch INSERT with an overridden key sends DEFAULT in every row" in {
        val s = Sql.insert[Account].values(Account(7L, "Ada"), Account(8L, "Bob")).overriding(_.id := Sql.default)
        assert(pgSql(s) == """INSERT INTO "account" ("id", "name") VALUES (DEFAULT, $1), (DEFAULT, $2) RETURNING "id"""")
    }

    // An override on `partialValues` replaces the value the caller wrote for that column, rather than being
    // dropped. Nothing is added: the emitted column list stays the caller's.
    "PG partialValues INSERT applies an override to a column it already names" in {
        val s = Sql.insert[Account].partialValues(_.id := 7L, _.name := "Ada").overriding(_.id := Sql.default)
        assert(pgSql(s) == """INSERT INTO "account" ("id", "name") VALUES (DEFAULT, $1) RETURNING "id"""")
    }

    // An INSERT ... SELECT has no cell to replace and no `DEFAULT` in a SELECT list on either flavor, so the
    // override is refused rather than silently dropped.
    "PG fromSelect INSERT with an override fails the render" in {
        val s = Sql.insert[Account]
            .fromSelect(_.name)(Sql.from[Account]("a").select(c => c.a.name))
            .overriding(_.id := Sql.default)
        val ex = intercept[SqlUnsupportedDialectFeatureException](s.render(PostgresDialect))
        assert(
            ex.feature == "INSERT ... SELECT with an overridden column",
            s"expected the overridden-column feature name, got: ${ex.feature}"
        )
    }

    // ── 5. The auto-key column name is the SQL name, not the Scala field name ──
    //
    // RETURNING emits an identifier, so it has to resolve renames and the naming strategy exactly as the emitted
    // column list does. A raw field name here would name a column the same statement had just called something
    // else, and the server would reject the statement.

    case class AuditEntry(entryId: Long, note: String)

    case class Ticket(@column("ticket_id") id: Long, subject: String)

    "PG INSERT under an in-scope casing returns the cased column name" in {
        // The casing governs tables and columns alike, so the table and every column are snake_cased,
        // RETURNING's included.
        given SqlNaming = SqlNaming.SnakeCase
        val s           = Sql.insert[AuditEntry].values(AuditEntry(0L, "opened"))
        assert(pgSql(s) == """INSERT INTO "audit_entry" ("entry_id", "note") VALUES ($1, $2) RETURNING "entry_id"""")
    }

    "PG INSERT with a renamed key column returns the renamed column" in {
        val s = Sql.insert[Ticket].values(Ticket(0L, "printer jam"))
        assert(pgSql(s) == """INSERT INTO "ticket" ("ticket_id", "subject") VALUES ($1, $2) RETURNING "ticket_id"""")
    }

end PostgresDialectInsertRenderTest
