package kyo

import scala.compiletime.testing.typeChecks

/** Pins what is reachable through which client type, and what is not.
  *
  * The guarantee is that a portable program cannot accidentally depend on one engine: a value typed [[SqlClient]] exposes only the portable
  * surface, and every engine-only member lives on the concrete subclass that can answer it. Each negative check below is paired with the
  * positive one on the owning class, so a failure means the member moved rather than the snippet breaking for an unrelated reason.
  */
class SqlClientVisibilityTest extends Test:

    "COPY is reachable on PostgresClient and not through an SqlClient value" in {
        assert(
            typeChecks(
                """import kyo.*
def probe(client: PostgresClient)(using Frame): Unit =
    val _ = client.copyOut("COPY person TO STDOUT")"""
            ),
            "copyOut must resolve on PostgresClient, which owns it"
        )
        assert(
            !typeChecks(
                """import kyo.*
def probe(client: SqlClient)(using Frame): Unit =
    val _ = client.copyOut("COPY person TO STDOUT")"""
            ),
            "copyOut must not resolve on the abstract SqlClient surface"
        )
    }

    "LISTEN/NOTIFY is reachable on PostgresClient and not through an SqlClient value" in {
        assert(
            typeChecks(
                """import kyo.*
def probe(client: PostgresClient)(using Frame): Unit =
    val _ = client.notifications("chan")"""
            ),
            "notifications must resolve on PostgresClient, which owns it"
        )
        assert(
            !typeChecks(
                """import kyo.*
def probe(client: SqlClient)(using Frame): Unit =
    val _ = client.notifications("chan")"""
            ),
            "notifications must not resolve on the abstract SqlClient surface"
        )
    }

    "LOAD DATA LOCAL INFILE is reachable on MysqlClient and not through an SqlClient value" in {
        assert(
            typeChecks(
                """import kyo.*
def probe(client: MysqlClient, data: Stream[Byte, Any])(using Frame): Unit =
    val _ = client.loadLocalInfile("LOAD DATA LOCAL INFILE 'x' INTO TABLE person", data)"""
            ),
            "loadLocalInfile must resolve on MysqlClient, which owns it"
        )
        assert(
            !typeChecks(
                """import kyo.*
def probe(client: SqlClient, data: Stream[Byte, Any])(using Frame): Unit =
    val _ = client.loadLocalInfile("LOAD DATA LOCAL INFILE 'x' INTO TABLE person", data)"""
            ),
            "loadLocalInfile must not resolve on the abstract SqlClient surface"
        )
    }

    "an engine-only member does not leak across the two concrete subclasses" in {
        assert(
            !typeChecks(
                """import kyo.*
def probe(client: MysqlClient)(using Frame): Unit =
    val _ = client.notifications("chan")"""
            ),
            "a Postgres-only member must not resolve on MysqlClient"
        )
        assert(
            !typeChecks(
                """import kyo.*
def probe(client: PostgresClient, data: Stream[Byte, Any])(using Frame): Unit =
    val _ = client.loadLocalInfile("LOAD DATA LOCAL INFILE 'x' INTO TABLE person", data)"""
            ),
            "a MySQL-only member must not resolve on PostgresClient"
        )
    }

    "the internalExecute dispatch triple is reachable from inside the kyo package" in {
        // The `.runStatic` splice emits these three into the user's call site, which is why they are `private[kyo]`
        // rather than private. This test compiles in package `kyo` and is the in-package half of that bound; the
        // cross-package half is an ABI snapshot, which no in-package snippet can express.
        assert(
            typeChecks(
                """import kyo.*
def probe(client: SqlClient)(using SqlSchema[Long], Frame): Unit =
    val _ = client.internalExecuteQuery[Long]("SELECT 1", Chunk.empty, client.config)
    val _ = client.internalExecuteUpdate("DELETE FROM person", Chunk.empty, client.config)
    val _ = client.internalExecuteInsert("INSERT INTO person VALUES (1)", Chunk.empty, client.config)"""
            ),
            "the internalExecute triple must resolve from inside the kyo package"
        )
    }

    "the cancel-handle API is gone, and the fiber is the handle instead" in {
        // There is no cancellation value for a caller to store and pass back to `cancel(handle)`, which would be an
        // axis of its own that ordinary fiber interruption does not reach. Neither spelling exists; the idioms that
        // replace them are exercised in SqlClientInterruptTest.
        assert(
            !typeChecks(
                """import kyo.*
def probe(client: PostgresClient)(using Frame): Unit =
    val _ = client.cancellableQuery("SELECT pg_sleep(5)")"""
            ),
            "cancellableQuery must not resolve on any client"
        )
        assert(
            !typeChecks("""import kyo.*
def probe(handle: SqlClient.CancelHandle): Unit = ()"""),
            "SqlClient.CancelHandle must not resolve as a type"
        )
    }

    "txLocal starts Absent, so a statement outside a transaction leases from the pool" in {
        SqlClient.txLocal.use { ctx =>
            assert(ctx == Absent, s"txLocal must start Absent, was $ctx")
        }
    }

end SqlClientVisibilityTest
