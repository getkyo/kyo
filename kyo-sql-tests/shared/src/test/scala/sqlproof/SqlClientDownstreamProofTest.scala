package sqlproof

import kyo.*
import scala.compiletime.testing.typeChecks

/** Proof that the client surface behaves correctly from a package outside the `kyo` hierarchy.
  *
  * Two properties can only be established from here. First, [[kyo.SqlClient.init]] is `inline` and its body reaches members that are
  * `private[kyo]`: the registry lookup and the factory's open entries. If qualified-private visibility were re-checked where the inline body
  * lands, every downstream call site would fail to compile, and no test inside `kyo` could reveal it. Second, the members that are
  * deliberately closed stay closed: an in-package test sees `private[kyo]` members and so cannot check that a user does not.
  *
  * This file is a permanent regression guard on both, and it is a `kyo`-free package on purpose. Adding `import kyo.*` is the only concession;
  * everything it names is what a downstream user names.
  */
class SqlClientDownstreamProofTest extends kyo.test.Test[Any]:

    "the inline init family expands at a downstream call site" in {
        assert(
            typeChecks("""import kyo.*
def probe(using Frame): SqlClient < (Async & Scope & Abort[SqlException]) =
    SqlClient.init("postgres://u:p@localhost:5432/db")"""),
            "SqlClient.init must expand outside the kyo package; its body reaches private[kyo] members through inline accessors"
        )
        assert(
            typeChecks("""import kyo.*
def probe(using Frame): SqlClient < (Async & Abort[SqlException]) =
    SqlClient.initUnscoped("postgres://u:p@localhost:5432/db")"""),
            "SqlClient.initUnscoped must expand outside the kyo package"
        )
        assert(
            typeChecks("""import kyo.*
def probe(using Frame): Long < (Async & Scope & Abort[SqlException]) =
    SqlClient.initWith("postgres://u:p@localhost:5432/db")(_.executeRaw("SELECT 1"))"""),
            "SqlClient.initWith must expand outside the kyo package"
        )
    }

    "the concrete factories expand at a downstream call site" in {
        assert(
            typeChecks("""import kyo.*
def probe(using Frame): PostgresClient < (Async & Scope & Abort[SqlException]) =
    PostgresClient.init("postgres://u:p@localhost:5432/db")"""),
            "PostgresClient.init must be usable outside the kyo package"
        )
        assert(
            typeChecks("""import kyo.*
def probe(using Frame): MysqlClient < (Async & Scope & Abort[SqlException]) =
    MysqlClient.init("mysql://u:p@localhost:3306/db")"""),
            "MysqlClient.init must be usable outside the kyo package"
        )
    }

    "the splice-emitted dispatch triple is not part of the downstream surface" in {
        assert(
            !typeChecks("""import kyo.*
def probe(client: SqlClient)(using SqlSchema[Long], Frame): Unit =
    val _ = client.internalExecuteQuery[Long]("SELECT 1", Chunk.empty, client.config)"""),
            "internalExecuteQuery is private[kyo] and must not resolve for a downstream caller"
        )
        assert(
            !typeChecks("""import kyo.*
def probe(client: SqlClient)(using Frame): Unit =
    val _ = client.internalExecuteUpdate("DELETE FROM person", Chunk.empty, client.config)"""),
            "internalExecuteUpdate is private[kyo] and must not resolve for a downstream caller"
        )
    }

    "the ambient slots are not part of the downstream surface" in {
        // The client a statement runs on is the DB effect, whose payload a downstream caller reaches only through the
        // public accessors. `DB.state` is the internal read the `.run` surface emits at every call site, so it stays
        // closed here for the same reason the dispatch triple above does. Both directions are checked: an accessor
        // that does resolve makes the negative half evidence about visibility rather than about a snippet that fails
        // for any reason at all.
        assert(
            !typeChecks("""import kyo.*
def probe(using Frame): Unit = val _ = DB.state"""),
            "DB.state is private[kyo] and must not resolve for a downstream caller"
        )
        assert(
            typeChecks("""import kyo.*
def probe(using Frame): SqlClient < DB = DB.client"""),
            "DB.client is the public door onto the effect's payload and must resolve for a downstream caller"
        )
        assert(
            !typeChecks("""import kyo.*
def probe: Unit = val _ = SqlClient.txLocal"""),
            "SqlClient.txLocal is private[kyo] and must not resolve for a downstream caller"
        )
    }

    "an engine-only method is unreachable through an SqlClient value downstream too" in {
        assert(
            !typeChecks("""import kyo.*
def probe(client: SqlClient)(using Frame): Unit =
    val _ = client.copyOut("COPY person TO STDOUT")"""),
            "copyOut must not resolve on the abstract SqlClient surface"
        )
        assert(
            typeChecks("""import kyo.*
def probe(client: PostgresClient)(using Frame): Unit =
    val _ = client.copyOut("COPY person TO STDOUT")"""),
            "copyOut must resolve on PostgresClient, which owns it"
        )
    }

end SqlClientDownstreamProofTest
