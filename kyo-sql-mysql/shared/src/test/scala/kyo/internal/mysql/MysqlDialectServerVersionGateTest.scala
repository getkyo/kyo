package kyo.internal.mysql

import kyo.*
import kyo.Test
import kyo.db.Idiom

/** Pins that the version a client captured from its server is the version its statements are rendered against.
  *
  * MySQL is where this is observable: three of its constructs arrived in specific 8.0 patch releases, so the same statement is valid
  * against one server and a parse error against an older one. Rendering the execute path at the dialect's capability floor instead would
  * send `INTERSECT` to a server that has never had it and turn a typed failure into a server-side syntax error.
  *
  * Every scenario uses a port nothing listens on with `minConnections = 0` and seeds the captured version directly, so no connection is
  * opened and what is under test is which version reaches the renderer.
  */
class MysqlDialectServerVersionGateTest extends Test:

    case class Item(id: Long, name: String)

    private val url                   = "mysql://alice:secret@localhost:9998/mydb"
    private val poolConfig: SqlConfig = SqlConfig.default.copy(minConnections = 0)

    private val intersecting = Sql.from[Item]("a").intersect(Sql.from[Item]("b"))

    /** A client that reports `version` without contacting anything, by filling the cache the accessor reads. */
    private def clientReporting(version: Idiom.ServerVersion)(using Frame): SqlClient < (Async & Scope & Abort[SqlException]) =
        SqlClient.init(url, poolConfig).flatMap { client =>
            client.runtime.serverVersionRef.set(Present(version)).andThen(client)
        }

    "query renders against the version the client reported, not the dialect's floor" in {
        Abort.run[SqlException](Scope.run {
            clientReporting(Idiom.ServerVersion(8, 0, 30)).flatMap(_.query(intersecting))
        }).map {
            case Result.Failure(e: SqlUnsupportedDialectFeatureException) =>
                assert(e.feature == "INTERSECT / EXCEPT", s"expected the gated construct named, got: ${e.feature}")
                assert(e.dialect == Idiom.Id("mysql"))
                assert(
                    e.serverVersion == Present(Idiom.ServerVersion(8, 0, 30)),
                    s"expected the reported version, got: ${e.serverVersion.map(_.show)}"
                )
            case other => fail(s"expected a typed unsupported-feature failure, got $other")
        }
    }

    "execute renders against the reported version too" in {
        Abort.run[SqlException](Scope.run {
            clientReporting(Idiom.ServerVersion(5, 7, 44)).flatMap(_.execute(intersecting))
        }).map {
            case Result.Failure(e: SqlUnsupportedDialectFeatureException) =>
                assert(e.serverVersion == Present(Idiom.ServerVersion(5, 7, 44)))
            case other => fail(s"expected a typed unsupported-feature failure, got $other")
        }
    }

    // The positive control: at a version that does support the construct the render succeeds and the statement
    // reaches the wire, where the closed port is what fails. Without this, a render that always failed would pass
    // the scenarios above.
    "a version that supports the construct renders and reaches the connection" in {
        Abort.run[SqlException](Scope.run {
            clientReporting(Idiom.ServerVersion(8, 0, 31)).flatMap(_.query(intersecting))
        }).map {
            case Result.Failure(_: SqlConnectionException) => succeed
            case other                                     => fail(s"expected the render to succeed and the connection to fail, got $other")
        }
    }

    // The pure render takes an explicit version and stays pure, so the same gate has to hold there as well.
    "the pure render honours an explicitly named version" in {
        val ex = intercept[SqlUnsupportedDialectFeatureException] {
            intersecting.render(MysqlDialect, Present(Idiom.ServerVersion(8, 0, 30)))
        }
        assert(ex.serverVersion == Present(Idiom.ServerVersion(8, 0, 30)))
        assert(intersecting.render(MysqlDialect, Present(Idiom.ServerVersion(8, 0, 31))).onlySql.get.contains("INTERSECT"))
    }

end MysqlDialectServerVersionGateTest
