package kyo

import kyo.internal.mysql.MysqlBackendFactory

/** Pins that URL scheme acceptance reads the backend's own declaration, and that refusing a foreign scheme survives it.
  *
  * MySQL declares no alias, so comparing the scheme against the literal `"mysql"` agrees with `MysqlBackendFactory`
  * only by coincidence: a literal comparison keeps passing even if the factory's declaration and the comparison site
  * separate.
  *
  * The claims leaf therefore asserts the AGREEMENT rather than a fixed string, so it keeps holding when an alias is added
  * and fails if the two declarations are ever separated again.
  */
class MysqlClientSchemeTest extends Test:

    /** Loopback with a port nothing listens on. A claimed scheme gets PAST validation and then fails on the refused
      * connection, and that difference is the observation: validation runs before any socket is opened.
      */
    private def refusedUrl(scheme: String): String = s"$scheme://user:pass@127.0.0.1:45918/db"

    private def rejectedAsUnparsable(rawUrl: String)(using Frame): Boolean < Async =
        Abort.run[SqlException](MysqlClient.initUnscoped(rawUrl)).map {
            case Result.Failure(_: SqlConnectionUrlParseException) => true
            case _                                                 => false
        }

    "the canonical scheme is accepted" in {
        rejectedAsUnparsable(refusedUrl("mysql")).map { rejected =>
            assert(!rejected, "mysql is the canonical scheme and must not be refused as unparsable")
        }
    }

    "claims agrees with the declared scheme and aliases, so adding an alias needs no second edit" in {
        assert(MysqlBackendFactory.claims(MysqlBackendFactory.scheme), "the canonical scheme must be claimed")
        MysqlBackendFactory.aliases.foreach { alias =>
            assert(MysqlBackendFactory.claims(alias), s"a declared alias must be claimed, $alias is not")
        }
        assert(!MysqlBackendFactory.claims("postgres"), "another engine's scheme must not be claimed")
    }

    "a scheme belonging to another engine is still refused, and names it" in {
        Abort.run[SqlException](MysqlClient.initUnscoped(refusedUrl("sqlite"))).map {
            case Result.Failure(e: SqlConnectionUrlParseException) =>
                assert(e.scheme == "sqlite", s"the refusal must name the offending scheme, got ${e.scheme}")
            case other =>
                fail(s"a scheme this backend does not claim must be refused, got $other")
        }
    }

end MysqlClientSchemeTest
