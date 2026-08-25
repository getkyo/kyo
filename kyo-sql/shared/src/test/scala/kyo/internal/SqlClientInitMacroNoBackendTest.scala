package kyo.internal

import kyo.*
import kyo.Test
import scala.compiletime.testing.typeChecks

/** Pins what [[kyo.internal.SqlClientInitMacro]] reports when the compile classpath carries no backend at all.
  *
  * `kyo-sql` declares no backend and the engine modules depend on it rather than the reverse, so this module's own test classpath is the only
  * one with zero `META-INF/services/kyo.db.Backend` entries, and therefore the only place the empty-classpath branch occurs. The
  * sibling suites reach the other branch: `SqlClientInitMacroTest` in `kyo-sql-postgres` and `SqlClientInitMacroSchemesTest` in
  * `kyo-sql-tests` both compile against at least one factory, so a literal URL there is either accepted or refused by naming the schemes that
  * ARE available.
  *
  * The second scenario is the one that keeps the diagnostic honest as the backend set grows. Backends are discovered rather than enumerated,
  * so a core message listing kyo's own two engines misdirects everyone using a third: it tells them to install PostgreSQL or MySQL support
  * when what they are missing is their own engine's artifact. Naming the concept is a property of the message, and this is what holds it.
  *
  * The branch decision itself is pinned here too, against [[kyo.internal.SqlClientInitMacro.verdictFor]] rather than against a classpath.
  * One of its four answers, the unread factory that leaves the classpath's claims unknown, cannot be staged by any real classpath: a
  * services fixture naming a class that fails to load has to live in a module, and the only module whose empty classpath tests the empty
  * branch is this one. Deciding on a described classpath reaches all four instead, including the two the compile leaves above cannot tell
  * apart: an empty scheme set that means no backend, and an empty scheme set that means nobody answered.
  */
class SqlClientInitMacroNoBackendTest extends Test:

    "a literal URL compiles even when the classpath carries no backend" in {
        // The missing backend is a WARNING at the call site, never an error: runtime discovery
        // (kyo.db.Backend.register) can still supply the backend, so the open must stay expressible.
        // The diagnostic's content is pinned on refusalFor below.
        assert(
            typeChecks("""import kyo.*
def probe(using Frame): Unit =
    val _ = Scope.run(SqlClient.initUnscoped("postgres://u:p@localhost:5432/db"))"""),
            "the empty classpath must not forbid the open; the diagnostic is a warning"
        )
    }

    "the no-backend diagnostic quotes the scheme and says what to add" in {
        val refusal = SqlClientInitMacro.refusalFor("postgres", SqlClientInitMacro.Verdict.NoBackend)
        assert(
            refusal.exists(_.contains("No SQL backend is on the compile classpath")),
            s"the warning must report the empty classpath, got: $refusal"
        )
        assert(refusal.exists(_.contains("postgres://")), s"the warning must quote back the scheme, got: $refusal")
        assert(
            refusal.exists(_.contains("Add the kyo-sql backend artifact for the engine this URL names")),
            s"the warning must say what to add, got: $refusal"
        )
        // The two refusals are separate states, so the empty classpath must not arrive dressed as the other one:
        // an enumeration of what is available is a claim this classpath has nothing to back.
        assert(refusal.exists(m => !m.contains("Available:")), s"the empty classpath must not enumerate schemes, got: $refusal")
    }

    "the no-backend diagnostic enumerates no engine" in {
        // A third backend's user sees this message too, and nothing in it may point them at kyo's two engines.
        val refusal = SqlClientInitMacro.refusalFor("sqlite", SqlClientInitMacro.Verdict.NoBackend)
        assert(
            refusal.exists(m => !m.contains("postgres") && !m.contains("mysql")),
            s"a core diagnostic must name no engine, got: $refusal"
        )
    }

    "a scheme a factory claims is openable" in {
        val declared = SqlClientInitMacro.Declared(Set("postgres", "postgresql"), Nil)
        val verdict  = SqlClientInitMacro.verdictFor("postgres", declared)
        assert(verdict == SqlClientInitMacro.Verdict.Claimed)
        assert(SqlClientInitMacro.refusalFor("postgres", verdict).isEmpty, "a claimed scheme must produce no compile error")
    }

    "a scheme no factory claims is unopenable once every declared factory has answered" in {
        val declared = SqlClientInitMacro.Declared(Set("postgres", "postgresql", "mysql"), Nil)
        val verdict  = SqlClientInitMacro.verdictFor("sqlite", declared)
        assert(verdict == SqlClientInitMacro.Verdict.Unclaimed(Set("postgres", "postgresql", "mysql")))
        val refusal = SqlClientInitMacro.refusalFor("sqlite", verdict)
        assert(
            refusal.exists(_.contains("No SQL backend on the compile classpath claims the scheme 'sqlite'")),
            s"the refusal must name the scheme asked for, got: $refusal"
        )
        assert(
            refusal.exists(_.contains("Available: mysql, postgres, postgresql.")),
            s"the refusal must enumerate every scheme the factories claim, got: $refusal"
        )
    }

    // A factory that did not answer is the one that would have claimed `sqlite`, so refusing here would name a set that
    // is neither complete nor evidence the scheme is absent. The refusal waits until the classpath has finished talking.
    "a scheme no factory claims is undecided while a declared factory is unread" in {
        val declared = SqlClientInitMacro.Declared(Set("postgres", "postgresql"), List("kyo.internal.sqlite.SqliteBackendFactory"))
        val verdict  = SqlClientInitMacro.verdictFor("sqlite", declared)
        assert(verdict == SqlClientInitMacro.Verdict.Undecided)
        assert(
            SqlClientInitMacro.refusalFor("sqlite", verdict).isEmpty,
            "a factory that did not answer may be the one claiming the scheme, so there is nothing to refuse yet"
        )
    }

    // The other arm of the same fact: the artifact IS on the compile classpath, it just could not be constructed on the
    // compiler's classloader, and the classloader that will run the program is a different one.
    "a classpath whose declared factories all went unread is undecided rather than empty" in {
        val declared = SqlClientInitMacro.Declared(Set.empty, List("kyo.internal.postgres.PostgresBackendFactory"))
        val verdict  = SqlClientInitMacro.verdictFor("postgres", declared)
        assert(verdict == SqlClientInitMacro.Verdict.Undecided)
        assert(
            SqlClientInitMacro.refusalFor("postgres", verdict).isEmpty,
            "a classpath carrying an artifact that did not load is not a classpath carrying no backend"
        )
    }

    // The control for the leaf above: the same empty scheme set IS the empty classpath when nothing was declared at all.
    "a classpath declaring no factory at all is the empty classpath" in {
        val declared = SqlClientInitMacro.Declared(Set.empty, Nil)
        val verdict  = SqlClientInitMacro.verdictFor("postgres", declared)
        assert(verdict == SqlClientInitMacro.Verdict.NoBackend)
        val refusal = SqlClientInitMacro.refusalFor("postgres", verdict)
        assert(
            refusal.exists(_.contains("No SQL backend is on the compile classpath")),
            s"the refusal must report the empty classpath, got: $refusal"
        )
        assert(!refusal.exists(_.contains("Available:")), s"an empty classpath has nothing to enumerate, got: $refusal")
    }

end SqlClientInitMacroNoBackendTest
