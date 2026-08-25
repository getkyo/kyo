package kyo.db

import kyo.*

/** Contract tests for [[Backend]]: the final scheme predicate [[Backend.claims]]. */
class BackendTest extends Test:

    private object StubIdiom extends Idiom:
        def id: Idiom.Id                         = Idiom.Id("stub")
        def capabilityFloor: Idiom.ServerVersion = Idiom.ServerVersion(1, 0, 0)
        def quoteIdent(ident: String): String    = "\"" + ident + "\""
        def placeholder(position: Int): String   = "?"
    end StubIdiom

    final private class StubBackend(canonical: String, further: Set[String]) extends Backend:
        def scheme: String       = canonical
        def aliases: Set[String] = further
        def dialect: Idiom       = StubIdiom
        def open(url: SqlConfig.Url, config: SqlConfig)(using Frame): SqlClient < (Async & Abort[SqlException]) =
            Abort.panic[SqlException](new UnsupportedOperationException("the stub backend opens nothing"))
    end StubBackend

    "claims accepts the canonical scheme".timeout(10.seconds) in {
        val backend = new StubBackend("postgres", Set("postgresql"))
        assert(backend.claims("postgres"))
    }

    "claims accepts every alias".timeout(10.seconds) in {
        val backend = new StubBackend("postgres", Set("postgresql", "pg"))
        assert(backend.claims("postgresql"))
        assert(backend.claims("pg"))
    }

    "claims rejects a scheme neither member names".timeout(10.seconds) in {
        val backend = new StubBackend("postgres", Set("postgresql"))
        assert(!backend.claims("mysql"))
    }

    "claims with no aliases accepts only the canonical scheme".timeout(10.seconds) in {
        val backend = new StubBackend("postgres", Set.empty)
        assert(backend.claims("postgres"))
        assert(!backend.claims("postgresql"))
    }

    // The contract defines claims as membership: true when the candidate is the scheme or one of the aliases. A candidate spelled in
    // another case is neither, so it is not claimed.
    "claims matches candidates exactly".timeout(10.seconds) in {
        val backend = new StubBackend("postgres", Set("postgresql"))
        assert(!backend.claims("Postgres"))
        assert(!backend.claims("POSTGRES"))
        assert(!backend.claims("PostgreSQL"))
    }

    "claims rejects the empty candidate".timeout(10.seconds) in {
        val backend = new StubBackend("postgres", Set("postgresql"))
        assert(!backend.claims(""))
    }

end BackendTest
