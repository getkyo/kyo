package kyo.internal.postgres

import kyo.*
import kyo.Test
import kyo.db.Idiom

/** Verifies what [[PostgresDialect]] answers about itself: its id, the oldest server version it renders for, which capabilities it claims at
  * which versions, and how it quotes an identifier.
  *
  * Also that a freshly constructed instance answers exactly as the shared one does, because the static-render macro builds it by name from the
  * services file through its public zero-argument constructor, and a dialect carrying per-instance state would diverge there.
  */
class PostgresDialectTest extends Test:

    case class Person(id: Long, name: String, age: Int)

    private val adults = Sql.from[Person]("p").where(c => c.p.age >= 18).select(c => c.p.name)

    // The dialect ids are hard-coded as string literals by the extension channel that carries Postgres-only types
    // (`Idiom.Id("postgres")` in PostgresTypes' HStore and Range givens) and by every typed failure naming a
    // flavor. These two scenarios are what stops the two spellings drifting apart.
    "postgresDialectIdIsTheSpellingTheExtensionChannelUses" in {
        assert(PostgresDialect.id == Idiom.Id("postgres"))
        assert(PostgresDialect.id.value == "postgres")
    }

    "capabilityFloorsAreTheOldestVersionEachFlavorRendersFor" in {
        assert(PostgresDialect.capabilityFloor == Idiom.ServerVersion(11, 0, 0))
    }

    // The floor exists so that rendering without a known version produces SQL every supported server accepts. That
    // only holds if no gated capability is closed at the floor, on either dialect.
    "everyGatedCapabilityIsOpenAtTheCapabilityFloor" in {
        val pg = PostgresDialect.capabilityFloor
        assert(PostgresDialect.supportsLateral(pg))
        assert(PostgresDialect.supportsRecursiveCte(pg))
        assert(PostgresDialect.supportsIntersectExcept(pg))
    }

    "postgresGatesAreOpenBelowItsFloorToo" in {
        // Postgres carries all three constructs at every version it has ever had, so its predicates do not depend on
        // the version at all. Asking below the floor proves the gate is not merely floor-relative.
        val ancient = Idiom.ServerVersion(9, 0, 0)
        assert(PostgresDialect.supportsLateral(ancient))
        assert(PostgresDialect.supportsRecursiveCte(ancient))
        assert(PostgresDialect.supportsIntersectExcept(ancient))
    }

    "returningIsAPostgresOnlyClauseAtEveryVersion" in {
        assert(PostgresDialect.supportsReturning)
    }

    "quoteIdentEscapesEachFlavorsOwnQuoteCharacter" in {
        assert(PostgresDialect.quoteIdent("name") == "\"name\"")
        assert(PostgresDialect.quoteIdent("a\"b") == "\"a\"\"b\"")
    }

    "renderProducesTheTargetFlavorsPlaceholderAndQuotingSyntax" in {
        assert(adults.render(PostgresDialect).onlySql.get == """SELECT "p"."name" FROM "person" "p" WHERE ("p"."age" >= $1)""")
    }

    "renderNumbersPostgresPlaceholdersInBindOrder" in {
        val q = Sql.from[Person]("p").where(c => c.p.age >= 18 && c.p.name != "")
        val r = q.render(PostgresDialect)
        assert(r.onlySql.get.contains("(\"p\".\"age\" >= $1)"))
        assert(r.onlySql.get.contains("(\"p\".\"name\" <> $2)"))
        assert(r.params.size == 2)
    }

    "renderCarriesTheBoundValuesAlongsideTheText" in {
        val r = adults.render(PostgresDialect)
        assert(r.params.size == 1)
        val bound: Sql.BoundValue[?] = r.params.head
        bound.value match
            case bound: Int => assert(bound == 18)
            case other      => fail(s"Expected the Int bind 18, got $other")
    }

    // The static-render macro renders a lifted AST through the same dialects the runtime path uses. If the two ever
    // diverged, the same query would execute differently depending on whether it was written with `.run` or
    // `.runDynamic`. STATIC-SQL-INLINE-ONLY: SqlStaticProbe.render needs a fully-inline expression, so the query is
    // spelled out again rather than referenced through `adults`.
    "theStaticRenderPathAgreesWithTheRuntimePathOnPostgres" in {
        val static = SqlStaticProbe.render(Sql.from[Person]("p").where(c => c.p.age >= 18).select(c => c.p.name))
        assert(static.sqlFor(PostgresDialect.id).get == adults.render(PostgresDialect).onlySql.get)
    }

    // The services file names these classes, and the static-render macro constructs them from that name through the
    // public zero-argument constructor. A freshly constructed dialect must therefore answer exactly as the shared
    // instance does; a dialect that carried per-instance state would fail here.
    "aFreshlyConstructedDialectAnswersAsTheSharedInstance" in {
        val pg = new PostgresDialect
        assert(pg.id == PostgresDialect.id)
        assert(pg.capabilityFloor == PostgresDialect.capabilityFloor)
        assert(adults.render(pg).onlySql.get == adults.render(PostgresDialect).onlySql.get)
    }

end PostgresDialectTest
