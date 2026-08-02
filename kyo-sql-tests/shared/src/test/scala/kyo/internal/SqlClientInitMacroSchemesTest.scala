package kyo.internal

import kyo.*
import kyo.Test
import kyo.db.Backend
import scala.compiletime.testing.typeCheckErrors

/** Pins that an unopenable URL names EVERY scheme the classpath can open, at the run-time boundary, and that a literal one compiles.
  *
  * The claimed-versus-unclaimed contrast itself is asserted in `kyo-sql-postgres` by `SqlClientInitMacroTest`, which needs only one backend to
  * establish it. What needs both, and therefore lives here, is the CONTENT of the refusal: the typed run-time failure has to enumerate the
  * available schemes, and an enumeration is only checkable against a classpath carrying more than one. In a single-backend module an assertion
  * that the message names `postgres` and `mysql` fails for a reason that has nothing to do with the code under test.
  *
  * The compile-time half is deliberately a WARNING, not a refusal: a literal scheme no classpath backend claims still compiles, because a
  * backend registered at startup can open it. Enforcement, and the scheme enumeration, therefore live at the run-time boundary. A library that
  * wraps `init` behind a non-inline signature turns every caller's literal into an opaque parameter, so the run-time path is the one most
  * users hit regardless.
  *
  * Both halves are checked against the registry this same classpath derives rather than against a written-out list, which is what makes
  * them checks of completeness. A factory the compile-time check cannot construct contributes nothing to the enumeration while still
  * contributing a factory to the registry, so a refusal listing only what happened to load is the divergence these two comparisons refuse.
  */
class SqlClientInitMacroSchemesTest extends Test:

    // The typed run-time refusal (the second leaf) enumerates what discovery reaches, which on Native needs both shipping
    // backends registered, since Native embeds only one services file. Set that up once before the leaves run. The
    // compile-time leaf is unaffected: it reads the derived registry, not runtime discovery.
    TestBackendRegistration.ensure()

    "a literal unclaimed scheme compiles rather than failing the build, because a runtime-registered backend can still claim it" in {
        // The scheme check for a literal URL is a compile-time WARNING, not an error: a backend registered at startup can
        // still open the scheme, so enforcement is at the run-time boundary (next leaf), not the compile boundary. A warning
        // is not an error, so `typeCheckErrors` carries none here. The enumeration content of the refusal is asserted against
        // the run-time typed failure below, which is where the classpath's claimed schemes are actually checked for completeness.
        val errors = typeCheckErrors("""import kyo.*
def probe(using Frame): Unit =
    val _ = Scope.run(SqlClient.initUnscoped("sqlite://u:p@localhost:5432/db"))""")
        assert(errors.isEmpty, s"a literal unclaimed scheme must compile (warn, not error), got: ${errors.map(_.message)}")
    }

    "a URL the compiler cannot read compiles, and the typed failure names every available scheme" in {
        // Passing through a val makes the argument opaque to the splice, which is the shape a library wrapper produces.
        val computed = "sqlite" + "://u:p@localhost:5432/db"
        Abort.run[SqlException](Scope.run(SqlClient.initUnscoped(computed))).map {
            case Result.Failure(e: SqlConnectionUnsupportedSchemeException) =>
                assert(e.scheme == "sqlite", s"expected the failure to name the scheme asked for, got ${e.scheme}")
                assert(
                    e.available.contains("postgres") && e.available.contains("mysql"),
                    s"expected the failure to list the available schemes, got ${e.available}"
                )
                // A superset rather than an equality, and only here: this tier answers with what the running program can
                // discover as well, which is allowed to exceed the compile classpath and never allowed to fall short of it.
                val claimed = Backend.Registry.current.factories.foldLeft(Set.empty[String])((acc, f) => acc ++ f.aliases + f.scheme)
                assert(
                    claimed.subsetOf(e.available.toSet),
                    s"expected the failure to list every scheme the compile classpath claims, got ${e.available} against $claimed"
                )
            case other =>
                fail(s"Expected SqlConnectionUnsupportedSchemeException for an unclaimed scheme, got $other")
        }
    }

end SqlClientInitMacroSchemesTest
