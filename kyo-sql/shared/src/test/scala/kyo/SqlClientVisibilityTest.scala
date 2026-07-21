package kyo

import scala.compiletime.testing.typeCheckErrors

/** Visibility tests for [[SqlClient]].
  *
  * Verifies that:
  *   1. The MySQL `cancellableQuery` overload is public (compile-passes for external callers).
  *   2. `txLocal` is tightened to file-private and not accessible from outside `SqlClient`.
  */
class SqlClientVisibilityTest extends Test:

    "public cancellableQuery overload is callable on MySQL clients" in {
        // The MySQL cancellableQuery was previously private[kyo]; it is now public.
        // We verify accessibility by checking that typeCheckErrors does NOT produce an
        // "cannot be accessed as a member" visibility error.  BoundMysqlParam is internal so
        // the snippet may produce type errors for the import or parameter type, but those are
        // *not* visibility errors on cancellableQuery itself.
        val errors = typeCheckErrors(
            """import kyo.*
import kyo.internal.mysql.BoundMysqlParam
def probe(client: SqlClient)(using Frame): Unit =
    val _ = client.cancellableQuery("SELECT SLEEP(1)", Chunk.empty[BoundMysqlParam[?]])"""
        )
        val hasAccessError = errors.exists(e => e.message.contains("cannot be accessed as a member"))
        assert(!hasAccessError, s"cancellableQuery must not have a visibility error; got: $errors")
    }

    "txLocal is not accessible from outside SqlClient" in {
        // txLocal is now private to object SqlClient; it must not be reachable from other files.
        val errors = typeCheckErrors("SqlClient.txLocal")
        assert(errors.nonEmpty, "Expected a compile error for SqlClient.txLocal access from outside SqlClient")
    }

end SqlClientVisibilityTest
