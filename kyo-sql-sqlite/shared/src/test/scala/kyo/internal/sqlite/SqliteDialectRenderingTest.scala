package kyo.internal.sqlite

import kyo.*
import kyo.Test
import kyo.db.Idiom

/** Pins every construct [[SqliteDialect]] renders differently from the baseline, and every construct it refuses.
  *
  * SQLite resolves types and names permissively instead of failing, so a baseline rendering the other two engines reject outright is here a
  * statement that runs and answers something else. Each leaf asserts the REPLACEMENT text or the typed refusal, never merely that rendering
  * succeeded.
  */
class SqliteDialectRenderingTest extends Test:

    case class Person(id: Long, name: String, age: Int, deptId: Long) derives SqlSchema
    case class Pair(x: Int, y: String) derives SqlSchema
    case class Flagged(id: Long, ok: Boolean, maybeOk: Maybe[Boolean]) derives SqlSchema

    private def sqlText(ast: Sql[?])(using Frame): String =
        SqliteDialect.render(ast, Absent, summon[Frame]).onlySql.get

    private def rendered(ast: Sql[?])(using Frame): Sql.Rendered =
        SqliteDialect.render(ast, Absent, summon[Frame])

    private val people = Sql.from[Person]("p")

    // `lateralSince` is a floor with no value meaning "never", so naming one would put a release that does not exist
    // into the failure.
    "LATERAL is refused at every version, with no required version named" in {
        val ex = intercept[SqlUnsupportedDialectFeatureException] {
            sqlText(Sql.lateral[Person]("l", Sql.from[Person]("x")))
        }
        assert(ex.requiredVersion.isEmpty, s"a refusal with no spelling must name no version, got ${ex.requiredVersion}")
        assert(ex.feature.contains("LATERAL"))
    }

    // SQLite's concurrency is one writer over the whole database, so a per-row clause has nothing to mean.
    "every row-locking clause is refused, the OF list and wait modes included" in {
        List(
            people.forUpdate,
            people.forUpdate("p"),
            people.forUpdateNoWait,
            people.forUpdateSkipLocked
        ).foreach { ast =>
            val ex = intercept[SqlUnsupportedDialectFeatureException](sqlText(ast))
            assert(ex.requiredVersion.isEmpty)
            assert(ex.feature.contains("FOR UPDATE"))
        }
        succeed
    }

    "the ALL forms of INTERSECT and EXCEPT are refused, while the plain forms render" in {
        val a = Sql.from[Person]("a")
        val b = Sql.from[Person]("b")
        val i = intercept[SqlUnsupportedDialectFeatureException](sqlText(a.intersectAll(b)))
        assert(i.feature.contains("INTERSECT ALL"))
        assert(i.requiredVersion.isEmpty)
        val e = intercept[SqlUnsupportedDialectFeatureException](sqlText(a.exceptAll(b)))
        assert(e.feature.contains("EXCEPT ALL"))
        assert(e.requiredVersion.isEmpty)
        assert(sqlText(a.intersect(b)).contains("INTERSECT"))
        assert(sqlText(a.except(b)).contains("EXCEPT"))
    }

    // There is no expression meaning "this column's declared default", so an assignment carrying one refuses rather
    // than rendering a keyword SQLite would read as a column name.
    "DEFAULT in an assignment is refused" in {
        val ex = intercept[SqlUnsupportedDialectFeatureException] {
            sqlText(Sql.update[Person].set(_.name := Sql.default).build)
        }
        assert(ex.feature.contains("DEFAULT"))
        assert(ex.requiredVersion.isEmpty)
    }

    // CAST picks a storage affinity by substring-matching the target name and returns a value of that class, so a
    // name SQLite does not know produces a wrong value with nothing red. Numeric answers TEXT, the affinity a decimal
    // is stored under.
    "castTypeName answers only for the storage classes SQLite has" in {
        assert(SqliteDialect.castTypeName(SqlType.Type.Text) == Present("TEXT"))
        assert(SqliteDialect.castTypeName(SqlType.Type.SmallInt) == Present("INTEGER"))
        assert(SqliteDialect.castTypeName(SqlType.Type.Int) == Present("INTEGER"))
        assert(SqliteDialect.castTypeName(SqlType.Type.BigInt) == Present("INTEGER"))
        assert(SqliteDialect.castTypeName(SqlType.Type.Float32) == Present("REAL"))
        assert(SqliteDialect.castTypeName(SqlType.Type.Float64) == Present("REAL"))
        assert(SqliteDialect.castTypeName(SqlType.Type.Bytes) == Present("BLOB"))
        assert(SqliteDialect.castTypeName(SqlType.Type.Numeric(Present(10), Present(2))) == Present("TEXT"))
    }

    // Each of these is a name SQLite accepts and silently resolves to the wrong affinity: INTERVAL contains INT and
    // yields an integer, BYTEA matches nothing and falls to NUMERIC, and BOOLEAN, UUID, JSONB, DATE and TIMESTAMP all
    // collapse to the same real. Extension matters most: the baseline passes an extension type's name straight through.
    "castTypeName refuses every target whose baseline spelling SQLite would silently mis-resolve" in {
        val refused = List(
            SqlType.Type.Boolean,
            SqlType.Type.Uuid,
            SqlType.Type.Json,
            SqlType.Type.Date,
            SqlType.Type.Time,
            SqlType.Type.TimeWithOffset,
            SqlType.Type.DateTime,
            SqlType.Type.Timestamp,
            SqlType.Type.CalendarInterval,
            SqlType.Type.Array(SqlType.Type.Text),
            SqlType.Type.Extension("hstore")
        )
        refused.foreach { t =>
            assert(SqliteDialect.castTypeName(t).isEmpty, s"$t must refuse rather than answer a name SQLite mis-resolves")
        }
        succeed
    }

    "a refused cast target fails typed at render time rather than reaching the server" in {
        val ex = intercept[SqlUnsupportedDialectFeatureException] {
            sqlText(Sql.from[Person]("p").select(c => c.p.age.cast[java.time.LocalDate]))
        }
        assert(ex.feature.contains("CAST"))
    }

    // SQLite rejects an alias column list on a derived table, and its own names for a VALUES list are column1,
    // column2, so the rename the projection needs moves into a wrapping SELECT. The row list is left alone, so bind
    // order is unchanged.
    "a VALUES source renames through a wrapping SELECT, leaving bind order untouched" in {
        val r   = rendered(Sql.values[Pair]("v", Pair(1, "a"), Pair(2, "b")))
        val sql = r.onlySql.get
        assert(sql.contains("(SELECT "), s"the rename must wrap the row list: $sql")
        assert(sql.contains("\"column1\" AS \"x\""), sql)
        assert(sql.contains("\"column2\" AS \"y\""), sql)
        assert(sql.contains("FROM (VALUES "), sql)
        assert(sql.contains("(?, ?), (?, ?)"), sql)
        assert(r.params.size == 4)
        given CanEqual[Any, Any] = CanEqual.derived
        assert((r.params(0).value: Any) == 1)
        assert((r.params(1).value: Any) == "a")
        assert((r.params(2).value: Any) == 2)
        assert((r.params(3).value: Any) == "b")
    }

    // SUBSTR rather than SUBSTRING because the latter is an alias added in 3.34, and the older name keeps this hook
    // out of the floor calculus. The whole projection is asserted because the FROM this rendering must avoid is
    // spelled the same as the query's own FROM clause.
    "SUBSTRING takes the comma form under the older SUBSTR name" in {
        val withLength = sqlText(Sql.from[Person]("p").select(c => c.p.name.substring(2, 3)))
        assert(withLength == "SELECT SUBSTR(\"p\".\"name\", ?, ?) FROM \"person\" \"p\"", withLength)
        val noLength = sqlText(Sql.from[Person]("p").select(c => c.p.name.substring(2)))
        assert(noLength == "SELECT SUBSTR(\"p\".\"name\", ?) FROM \"person\" \"p\"", noLength)
    }

    // Casting the dividend to NUMERIC, which is what PostgreSQL does, is a no-op here: NUMERIC affinity leaves an
    // integer an integer, and CAST(... AS REAL) is the spelling that answers 3.5 rather than 3.
    "both division arms cast the dividend to REAL, because NUMERIC affinity would leave it an integer" in {
        val integral = sqlText(Sql.from[Person]("p").select(c => c.p.age / 2))
        assert(integral.contains("CAST("), integral)
        assert(integral.contains(" AS REAL) / "), integral)
        val fractional = sqlText(Sql.from[Person]("p").select(c => Sql.literal(7.5) / Sql.literal(2.5)))
        assert(fractional.contains(" AS REAL) / "), fractional)
    }

    // DivideTruncating is deliberately NOT cast: truncating is what it asks for, so the baseline rendering is right.
    "the truncating division operator keeps the baseline rendering" in {
        val sql = sqlText(Sql.from[Person]("p").select(c => c.p.age.divideTruncating(2)))
        assert(!sql.contains("AS REAL"), s"truncating division must not be widened to REAL: $sql")
    }

    // SQLite's % truncates BOTH operands to integers before computing, so 7.5 % 2.5 is 1.0 where the other two
    // engines answer 0.0, typed real so nothing downstream flags it.
    "the remainder operator is lowered, because SQLite's % truncates both operands" in {
        val sql = sqlText(Sql.from[Person]("p").select(c => Sql.literal(7.5) % Sql.literal(2.5)))
        assert(!sql.contains("%"), s"the native operator truncates its operands and must not be emitted: $sql")
        assert(sql.contains("CAST("), sql)
        assert(sql.contains(" AS INTEGER)"), sql)
    }

    // IS UNKNOWN does not exist, and for a boolean-valued expression it means exactly IS NULL: NULL IS NULL is true,
    // a decided comparison is not, and a comparison against NULL is.
    "IS UNKNOWN lowers to IS NULL, which is exact for a boolean expression" in {
        val unknown = sqlText(Sql.from[Flagged]("f").where(c => c.f.maybeOk.isUnknown))
        assert(unknown.contains("IS NULL"), unknown)
        assert(!unknown.contains("UNKNOWN"), unknown)
        val notUnknown = sqlText(Sql.from[Flagged]("f").where(c => c.f.maybeOk.isNotUnknown))
        assert(notUnknown.contains("IS NOT NULL"), notUnknown)
        assert(!notUnknown.contains("UNKNOWN"), notUnknown)
    }

    // TRUE and FALSE are fallback constants on SQLite, live only when nothing in scope claims the name, and this
    // renders where the table's columns are in scope. A bare integer is never a column reference.
    "the IS TRUE family is spelled with integers, which no column can shadow" in {
        assert(sqlText(Sql.from[Flagged]("f").where(c => c.f.ok.isTrue)).contains("IS 1"))
        assert(sqlText(Sql.from[Flagged]("f").where(c => c.f.ok.isNotTrue)).contains("IS NOT 1"))
        assert(sqlText(Sql.from[Flagged]("f").where(c => c.f.ok.isFalse)).contains("IS 0"))
        assert(sqlText(Sql.from[Flagged]("f").where(c => c.f.ok.isNotFalse)).contains("IS NOT 0"))
    }

    // Same shadowing, and here it is a wrong ROW SET rather than a wrong predicate: over a table with a column named
    // `false` holding 1, WHERE (FALSE) returns the row, so an empty IN () would match every row instead of none.
    "an empty IN renders as an integer, so a column named false cannot invert it" in {
        val col     = Sql.Column["age", Int]("p", "age", "age")
        val plain   = sqlText(Sql.InValues(col, Chunk.empty[Sql.Term[Int]]))
        val negated = sqlText(Sql.NotInValues(col, Chunk.empty[Sql.Term[Int]]))
        assert(plain == "(0)", plain)
        assert(negated == "(1)", negated)
        assert(!plain.toUpperCase.nn.contains("FALSE"), plain)
        assert(!negated.toUpperCase.nn.contains("TRUE"), negated)
    }

    // NULL is the trap omission avoids: it happens to auto-assign for an INTEGER PRIMARY KEY, so substituting it
    // passes the generated-key tests and stores NULL instead of the declared default for every other column.
    "a defaulted column is dropped from the column list rather than spelled" in {
        val r   = rendered(Sql.insert[Person].values(Person(7L, "Alice", 30, 1L)).overriding(_.id := Sql.default))
        val sql = r.onlySql.get
        assert(sql.startsWith("INSERT INTO \"person\" (\"name\", \"age\", \"deptId\") VALUES (?, ?, ?)"), sql)
        assert(!sql.toUpperCase.nn.contains("DEFAULT"), sql)
        assert(r.params.size == 3, s"only the three kept columns bind, got ${r.params.size}")
        // The dropped column is the one SQLite auto-assigns, so it comes back through RETURNING.
        assert(sql.endsWith("RETURNING \"id\""), sql)
    }

    "a partial insert drops a defaulted column the same way" in {
        val sql = sqlText(Sql.insert[Person].partialValues(_.name := "amy", _.id := Sql.default))
        assert(sql.startsWith("INSERT INTO \"person\" (\"name\") VALUES (?)"), sql)
        assert(!sql.toUpperCase.nn.contains("DEFAULT"), sql)
        assert(sql.endsWith("RETURNING \"id\""), sql)
    }

    // SQLite reads the ON of ON CONFLICT as the start of a join constraint, a documented parsing ambiguity. A
    // trailing WHERE disambiguates it, but appending one to the fed query is wrong for a query already ending in a
    // WHERE, so the tautology goes on a wrapper.
    "an upsert fed by a SELECT wraps the query so its own WHERE cannot be disturbed" in {
        val base = Sql.insert[Person].fromSelect(_.id, _.name, _.age, _.deptId)(Sql.from[Person]("s"))
        val sql  = sqlText(base.onConflictDoNothing(_.id))
        assert(sql.contains("SELECT * FROM ("), sql)
        assert(sql.contains("\"kyo_upsert_src\""), sql)
        assert(sql.contains(" WHERE 1"), sql)
        assert(sql.indexOf(" WHERE 1") < sql.indexOf("ON CONFLICT"), s"the tautology must precede the conflict clause: $sql")
    }

    "the wrapper holds for a fed query that already carries its own WHERE" in {
        val fed = Sql.from[Person]("s").where(c => c.s.age >= 18)
        val sql = sqlText(Sql.insert[Person].fromSelect(_.id, _.name, _.age, _.deptId)(fed).onConflictDoNothing(_.id))
        assert(sql.contains("SELECT * FROM ("), sql)
        assert(sql.contains("\"s\".\"age\" >= ?"), s"the fed query keeps its own predicate: $sql")
        assert(sql.contains(" WHERE 1"), sql)
    }

    // An INSERT ... SELECT has no per-row cell to drop, so the omission that serves the VALUES forms has nowhere to
    // happen and an overridden column refuses instead of rendering a keyword.
    "an upsert fed by a SELECT refuses an overridden column rather than dropping it" in {
        val base = Sql.insert[Person].fromSelect(_.id, _.name, _.age, _.deptId)(Sql.from[Person]("s"))
        val ex   = intercept[SqlUnsupportedException](sqlText(base.onConflictDoNothing(_.id).overriding(_.id := Sql.default)))
        assert(ex.getMessage.nn.contains("overridden"), ex.getMessage.nn)
    }

end SqliteDialectRenderingTest
