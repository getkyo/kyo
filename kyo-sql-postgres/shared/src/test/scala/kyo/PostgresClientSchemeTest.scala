package kyo

import kyo.internal.postgres.PostgresBackendFactory

/** Pins that URL scheme acceptance reads the backend's own declaration, and that refusing a foreign scheme survives it.
  *
  * `PostgresBackendFactory` declares `postgresql` as an alias, so comparing the scheme against the literal `"postgres"`
  * refuses a URL the backend speaks. Nothing else in the suite reaches this validation: every `SqlConnectionUrlParseException`
  * leaf exercises `SqlConfig.Url.parse`, which accepts any scheme by design.
  *
  * The pair matters more than either half. Widening acceptance is easy to get wrong in the other direction, and rejecting a
  * genuinely foreign URL is the site's legitimate purpose, so the acceptance leaf alone would not show that refusal survives.
  */
class PostgresClientSchemeTest extends Test:

    /** Loopback with a port nothing listens on. A claimed scheme gets PAST validation and then fails on the refused
      * connection, and that difference is the observation: validation runs before any socket is opened, so the exception
      * type says which side answered.
      */
    private def refusedUrl(scheme: String): String = s"$scheme://user:pass@127.0.0.1:45917/db"

    private def rejectedAsUnparsable(rawUrl: String)(using Frame): Boolean < Async =
        Abort.run[SqlException](PostgresClient.initUnscoped(rawUrl)).map {
            case Result.Failure(_: SqlConnectionUrlParseException) => true
            case _                                                 => false
        }

    "every scheme the factory declares is accepted, canonical and alias alike" in {
        val claimed = PostgresBackendFactory.scheme :: PostgresBackendFactory.aliases.toList.sorted
        for
            canonical <- rejectedAsUnparsable(refusedUrl("postgres"))
            alias     <- rejectedAsUnparsable(refusedUrl("postgresql"))
        yield
            assert(claimed == List("postgres", "postgresql"), s"the declared set changed, extend this leaf: $claimed")
            assert(!canonical, "postgres is the canonical scheme and must not be refused as unparsable")
            assert(!alias, "postgresql is a declared alias of this backend and must not be refused as unparsable")
        end for
    }

    "a scheme belonging to another engine is still refused, and names it" in {
        Abort.run[SqlException](PostgresClient.initUnscoped(refusedUrl("sqlite"))).map {
            case Result.Failure(e: SqlConnectionUrlParseException) =>
                assert(e.scheme == "sqlite", s"the refusal must name the offending scheme, got ${e.scheme}")
            case other =>
                fail(s"a scheme this backend does not claim must be refused, got $other")
        }
    }

end PostgresClientSchemeTest
