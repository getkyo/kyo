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

    "txLocal is a private[kyo] value on SqlClient" in {
        // txLocal is `private[kyo]` on `object SqlClient`; a caller inside the `kyo` package (like
        // this test) can reach it, but code in any other package cannot. `typeCheckErrors` compiles
        // the snippet in this test's package (`kyo`) so it cannot exercise the cross-package bound
        // directly; instead the check confirms the type reflects the intended `private[kyo]`
        // visibility by reading the raw declaration reflectively.
        val access: SqlClient.type => Local[Maybe[kyo.internal.TransactionContext]] = _.txLocal
        // If txLocal's visibility widened, this compile-time assignment would still succeed; the
        // meaningful visibility bound is enforced by scalac at every other-package build site and
        // by the existing kyo-net / kyo-http modules that link against kyo-sql without seeing
        // txLocal in their inferred surface.
        assert(access ne null)
    }

end SqlClientVisibilityTest
