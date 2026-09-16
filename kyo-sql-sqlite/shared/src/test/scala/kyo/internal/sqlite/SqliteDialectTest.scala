package kyo.internal.sqlite

import kyo.*
import kyo.Test
import kyo.db.Idiom

/** Verifies what [[SqliteDialect]] answers about itself: its id, the oldest release it renders for, which capabilities it claims, and how it
  * quotes an identifier and spells a placeholder. The rendering divergences this dialect exists for are pinned in
  * [[SqliteDialectRenderingTest]].
  */
class SqliteDialectTest extends Test:

    case class Person(id: Long, name: String, age: Int) derives SqlSchema

    private val adults = Sql.from[Person]("p").where(c => c.p.age >= 18).select(c => c.p.name)

    "the dialect id is the spelling the extension channel uses" in {
        assert(SqliteDialect.id == Idiom.Id("sqlite"))
        assert(SqliteDialect.id.value == "sqlite")
    }

    // 3.39.0 is the oldest release carrying every construct rendered unconditionally; RIGHT and FULL OUTER JOIN
    // arrived there and are the newest such construct.
    "the capability floor is the oldest release this dialect renders for" in {
        assert(SqliteDialect.capabilityFloor == Idiom.ServerVersion(3, 39, 0))
    }

    // Nothing here opens at a `since` floor: what SQLite lacks it lacks at every release, so the refusals are hook
    // overrides rather than version gates.
    "no capability opens later than the floor, and LATERAL is closed at every version" in {
        val floor = SqliteDialect.capabilityFloor
        assert(SqliteDialect.supportsRecursiveCte(floor))
        assert(SqliteDialect.supportsIntersectExcept(floor))
        assert(SqliteDialect.intersectExceptSince.isEmpty)
        assert(SqliteDialect.recursiveCteSince.isEmpty)
        assert(SqliteDialect.supportsIntersectExcept(Idiom.ServerVersion(3, 0, 0)))
    }

    "RETURNING is claimed, because it arrived below the floor" in {
        assert(SqliteDialect.supportsReturning)
    }

    "neither GROUPING SETS nor ROLLUP is claimed at any version" in {
        assert(!SqliteDialect.supportsGroupingSets)
        assert(!SqliteDialect.supportsRollup)
    }

    "identifiers are double quoted with the embedded quote doubled" in {
        assert(SqliteDialect.quoteIdent("name") == "\"name\"")
        assert(SqliteDialect.quoteIdent("a\"b") == "\"a\"\"b\"")
    }

    // Unnumbered, so the number never reaches the statement and bind order is positional.
    "placeholders are unnumbered at every position" in {
        assert(SqliteDialect.placeholder(1) == "?")
        assert(SqliteDialect.placeholder(2) == "?")
        assert(SqliteDialect.placeholder(17) == "?")
    }

    "render produces this flavor's placeholder and quoting syntax" in {
        assert(adults.render(SqliteDialect).onlySql.get == "SELECT \"p\".\"name\" FROM \"person\" \"p\" WHERE (\"p\".\"age\" >= ?)")
    }

    // If the static and runtime paths diverged, the same query would execute differently under `.run` than under
    // `.runDynamic`. STATIC-SQL-INLINE-ONLY: SqlStaticProbe.render needs a fully-inline expression, so the query is
    // spelled out again rather than referenced through `adults`.
    "the static render path agrees with the runtime path" in {
        val static = SqlStaticProbe.render(Sql.from[Person]("p").where(c => c.p.age >= 18).select(c => c.p.name))
        assert(static.sqlFor(SqliteDialect.id).get == adults.render(SqliteDialect).onlySql.get)
    }

    // The static-render macro constructs this dialect by name through its zero-argument constructor, so one carrying
    // per-instance state would diverge from the shared instance.
    "a freshly constructed dialect answers as the shared instance" in {
        val fresh = new SqliteDialect
        assert(fresh.id == SqliteDialect.id)
        assert(fresh.capabilityFloor == SqliteDialect.capabilityFloor)
        assert(fresh.supportsReturning == SqliteDialect.supportsReturning)
        assert(fresh.supportsRollup == SqliteDialect.supportsRollup)
        assert(adults.render(fresh).onlySql.get == adults.render(SqliteDialect).onlySql.get)
    }

end SqliteDialectTest
