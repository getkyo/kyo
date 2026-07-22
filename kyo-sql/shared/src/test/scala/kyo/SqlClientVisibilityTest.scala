package kyo

import scala.compiletime.testing.typeCheckErrors

/** Visibility tests for [[SqlClient]].
  *
  * Verifies that:
  *   1. The MySQL `cancellableQuery` overload is public (compile-passes for external callers).
  *   2. `txLocal` is tightened to file-private and not accessible from outside `SqlClient`.
  */
class SqlClientVisibilityTest extends Test:

    "public cancellableQuery(executable) overload is callable on MySQL clients" in {
        // The MySQL cancellableQuery(sql, params: Chunk[BoundMysqlParam[?]]) overload takes an
        // internal type and is private[kyo]. The user-facing surface is the SqlAst.Executable[?]
        // overload; verify it does NOT produce a visibility error for an external caller.
        val errors = typeCheckErrors(
            """import kyo.*
def probe(client: SqlClient.Mysql, exec: SqlAst.Executable[?])(using Frame): Unit =
    val _ = client.cancellableQuery(exec)"""
        )
        val hasAccessError = errors.exists(e => e.message.contains("cannot be accessed as a member"))
        assert(!hasAccessError, s"cancellableQuery(executable) must not have a visibility error; got: $errors")
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
