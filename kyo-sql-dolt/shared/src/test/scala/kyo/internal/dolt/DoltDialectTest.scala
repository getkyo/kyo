package kyo.internal.dolt

import kyo.*
import kyo.Test
import kyo.db.Idiom
import kyo.internal.mysql.MysqlDialect

/** Pins what [[DoltDialect]] answers about itself, and in particular the two places it must NOT be MySQL.
  *
  * The rest of the leaves assert that it IS MySQL: this dialect exists to inherit, so a divergence appearing without a measurement behind
  * it is a regression rather than a feature.
  */
class DoltDialectTest extends Test:

    case class Person(id: Long, name: String, age: Int) derives SqlSchema

    private val adults = Sql.from[Person]("p").where(c => c.p.age >= 18).select(c => c.p.name)

    "the dialect has its own id rather than MySQL's" in {
        assert(DoltDialect.id == Idiom.Id("dolt"))
        assert(DoltDialect.id != MysqlDialect.id)
    }

    "ROLLUP is refused, which is where this engine is not MySQL" in {
        assert(!DoltDialect.supportsRollup)
        assert(MysqlDialect.supportsRollup, "the divergence is only meaningful while MySQL still has it")
        val ex = intercept[SqlUnsupportedDialectFeatureException] {
            Sql.from[Person]("p").groupByRollup(c => c.p.name).select(v => (v.name, v.age.sum)).render(DoltDialect).onlySql.get
        }
        assert(ex.requiredVersion.isEmpty, "no version introduces it, so none may be named")
    }

    "the capability floor is MySQL's, because that is the version the server reports" in {
        assert(DoltDialect.capabilityFloor == MysqlDialect.capabilityFloor)
        assert(DoltDialect.capabilityFloor == Idiom.ServerVersion(8, 0, 31))
    }

    "everything else is inherited, so the rendered SQL is byte-identical to MySQL's" in {
        assert(adults.render(DoltDialect).onlySql.get == adults.render(MysqlDialect).onlySql.get)
        assert(DoltDialect.quoteIdent("a`b") == MysqlDialect.quoteIdent("a`b"))
        assert(DoltDialect.placeholder(1) == MysqlDialect.placeholder(1))
        assert(DoltDialect.supportsReturning == MysqlDialect.supportsReturning)
        assert(DoltDialect.supportsGroupingSets == MysqlDialect.supportsGroupingSets)
    }

    // The services file names this class and the static-render macro constructs it from that name.
    "a freshly constructed dialect answers as the shared instance" in {
        val fresh = new DoltDialect
        assert(fresh.id == DoltDialect.id)
        assert(fresh.capabilityFloor == DoltDialect.capabilityFloor)
        assert(fresh.supportsRollup == DoltDialect.supportsRollup)
        assert(adults.render(fresh).onlySql.get == adults.render(DoltDialect).onlySql.get)
    }

end DoltDialectTest
