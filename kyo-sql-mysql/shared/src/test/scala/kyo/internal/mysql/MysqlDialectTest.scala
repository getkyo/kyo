package kyo.internal.mysql

import kyo.*
import kyo.Test
import kyo.db.Idiom

/** Verifies what [[MysqlDialect]] answers about itself: its id, the oldest server version it renders for, which capabilities it claims at
  * which versions, and how it quotes an identifier.
  *
  * Also that a freshly constructed instance answers exactly as the shared one does, because the static-render macro builds it by name from the
  * services file through its public zero-argument constructor, and a dialect carrying per-instance state would diverge there.
  */
class MysqlDialectTest extends Test:

    case class Person(id: Long, name: String, age: Int)

    private val adults = Sql.from[Person]("p").where(c => c.p.age >= 18).select(c => c.p.name)

    "mysqlDialectIdIsTheSpellingTheExtensionChannelUses" in {
        assert(MysqlDialect.id == Idiom.Id("mysql"))
        assert(MysqlDialect.id.value == "mysql")
    }

    "capabilityFloorsAreTheOldestVersionEachFlavorRendersFor" in {
        assert(MysqlDialect.capabilityFloor == Idiom.ServerVersion(8, 0, 31))
    }

    // The floor exists so that rendering without a known version produces SQL every supported server accepts. That
    // only holds if no gated capability is closed at the floor, on either dialect.
    "everyGatedCapabilityIsOpenAtTheCapabilityFloor" in {
        val my = MysqlDialect.capabilityFloor
        assert(MysqlDialect.supportsLateral(my))
        assert(MysqlDialect.supportsRecursiveCte(my))
        assert(MysqlDialect.supportsIntersectExcept(my))
    }

    "mysqlLateralGateOpensAt8_0_14" in {
        assert(MysqlDialect.supportsLateral(Idiom.ServerVersion(8, 0, 14)))
        assert(!MysqlDialect.supportsLateral(Idiom.ServerVersion(8, 0, 13)))
        assert(!MysqlDialect.supportsLateral(Idiom.ServerVersion(5, 7, 44)))
    }

    "mysqlRecursiveCteGateOpensAt8_0_0" in {
        assert(MysqlDialect.supportsRecursiveCte(Idiom.ServerVersion(8, 0, 0)))
        assert(!MysqlDialect.supportsRecursiveCte(Idiom.ServerVersion(5, 7, 44)))
    }

    "mysqlIntersectExceptGateOpensAt8_0_31" in {
        assert(MysqlDialect.supportsIntersectExcept(Idiom.ServerVersion(8, 0, 31)))
        assert(!MysqlDialect.supportsIntersectExcept(Idiom.ServerVersion(8, 0, 30)))
    }

    "returningIsAPostgresOnlyClauseAtEveryVersion" in {
        assert(!MysqlDialect.supportsReturning)
    }

    "quoteIdentEscapesEachFlavorsOwnQuoteCharacter" in {
        assert(MysqlDialect.quoteIdent("name") == "`name`")
        assert(MysqlDialect.quoteIdent("a`b") == "`a``b`")
    }

    "renderProducesTheTargetFlavorsPlaceholderAndQuotingSyntax" in {
        assert(adults.render(MysqlDialect).onlySql.get == "SELECT `p`.`name` FROM `person` `p` WHERE (`p`.`age` >= ?)")
    }

    // An Absent version is the whole reason the floor is on the dialect: it must resolve to the floor, not to some
    // lowest-possible version that would close every gate.
    "renderWithoutAVersionTargetsTheCapabilityFloor" in {
        val left  = Sql.from[Person]("a")
        val right = Sql.from[Person]("b")
        val open  = left.intersect(right).render(MysqlDialect)
        assert(open.onlySql.get.contains("INTERSECT"))
        val ex = intercept[SqlUnsupportedDialectFeatureException] {
            left.intersect(right).render(MysqlDialect, Present(Idiom.ServerVersion(8, 0, 30)))
        }
        assert(ex.requiredVersion == Present(Idiom.ServerVersion(8, 0, 31)))
        assert(ex.serverVersion == Present(Idiom.ServerVersion(8, 0, 30)))
    }

    // The static-render macro renders a lifted AST through the same dialects the runtime path uses. If the two ever
    // diverged, the same query would execute differently depending on whether it was written with `.run` or
    // `.runDynamic`. STATIC-SQL-INLINE-ONLY: SqlStaticProbe.render needs a fully-inline expression, so the query is
    // spelled out again rather than referenced through `adults`.
    "theStaticRenderPathAgreesWithTheRuntimePathOnMysql" in {
        val static = SqlStaticProbe.render(Sql.from[Person]("p").where(c => c.p.age >= 18).select(c => c.p.name))
        assert(static.sqlFor(MysqlDialect.id).get == adults.render(MysqlDialect).onlySql.get)
    }

    // The services file names these classes, and the static-render macro constructs them from that name through the
    // public zero-argument constructor. A freshly constructed dialect must therefore answer exactly as the shared
    // instance does; a dialect that carried per-instance state would fail here.
    "aFreshlyConstructedDialectAnswersAsTheSharedInstance" in {
        val my = new MysqlDialect
        assert(my.id == MysqlDialect.id)
        assert(my.capabilityFloor == MysqlDialect.capabilityFloor)
        assert(adults.render(my).onlySql.get == adults.render(MysqlDialect).onlySql.get)
    }

end MysqlDialectTest
