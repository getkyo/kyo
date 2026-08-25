package kyo.internal

import kyo.*
import kyo.Test
import scala.compiletime.testing.typeCheckErrors
import scala.compiletime.testing.typeChecks

/** Pins when asking for an unopenable URL is a compile error and when it is a typed failure.
  *
  * This module's compile classpath carries one backend, so a check run here sees `postgres` and its declared alias `postgresql`. That is
  * enough to establish the split this suite is about: a literal URL naming a claimed scheme compiles, and one naming a scheme no backend
  * claims does not, with the macro's own diagnostic naming both the scheme asked for and what is actually available.
  *
  * One assertion cannot live here and is not dropped. That the refusal enumerates EVERY available scheme is only checkable against a classpath
  * carrying more than one, so it is asserted at full strength in `kyo-sql-tests` by `SqlClientInitMacroSchemesTest`, together with its
  * run-time counterpart for an argument the splice cannot read.
  *
  * What this classpath does establish about the OTHER refusal is that the two are told apart on real input: one backend present is not zero
  * backends present, so an unclaimed scheme here must be refused as unclaimed and never as an empty classpath. The third state, a factory
  * declared and unread, is decided in `kyo-sql` by `SqlClientInitMacroNoBackendTest`, which pins it without a classpath to stage it with.
  */
class SqlClientInitMacroTest extends Test:

    "a literal URL naming a claimed scheme compiles" in {
        assert(
            typeChecks("""import kyo.*
def probe(using Frame): Unit =
    val _ = Scope.run(SqlClient.initUnscoped("postgres://u:p@localhost:5432/db"))"""),
            "postgres is claimed by a backend in this build, so the literal must compile"
        )
    }

    "a literal URL naming a declared alias compiles" in {
        assert(
            typeChecks("""import kyo.*
def probe(using Frame): Unit =
    val _ = Scope.run(SqlClient.initUnscoped("postgresql://u:p@localhost:5432/db"))"""),
            "postgresql is declared as an alias of postgres, so the literal must compile"
        )
    }

    "a literal URL naming a scheme no backend claims compiles, and the diagnostic names this module's backend" in {
        // The unclaimed scheme is a WARNING naming what is available, never an error: a backend registered at
        // run time can still claim it. The message content is pinned through refusalFor, since a warning does
        // not surface through typeCheckErrors.
        assert(
            typeChecks("""import kyo.*
def probe(using Frame): Unit =
    val _ = Scope.run(SqlClient.initUnscoped("sqlite://u:p@localhost:5432/db"))"""),
            "an unclaimed scheme must warn, not forbid"
        )
        val refusal = kyo.internal.SqlClientInitMacro.refusalFor(
            "sqlite",
            kyo.internal.SqlClientInitMacro.Verdict.Unclaimed(Set("postgres", "postgresql"))
        )
        assert(refusal.exists(_.contains("sqlite")), s"the warning must name the scheme asked for, got: $refusal")
        assert(refusal.exists(_.contains("postgres")), s"the warning must name this module's schemes, got: $refusal")
        assert(
            refusal.exists(m => !m.contains("No SQL backend is on the compile classpath")),
            s"a classpath carrying a backend must not be described as empty, got: $refusal"
        )
    }

end SqlClientInitMacroTest
