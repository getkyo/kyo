package com.example.stub

import kyo.*
import kyo.Test
import kyo.internal.TestBackendRegistration

/** Pins the out-of-tree stub backend against the public SPI: the two override-surface guards of Section 7, assembly through the public
  * `Runtime.init`, and the exception-extensibility of Section 12. Living in `com.example.stub` rather than `kyo`, its mere compilation proves
  * the whole SPI is reachable from a backend that depends on kyo-sql as an ordinary library (Section 18.4).
  */
class StubBackendTest extends Test:

    case class Row(id: Long, name: String) derives SqlSchema

    "the select interception prepends its marker, then super renders the baseline select" in {
        val sql = Sql.from[Row]("r").select(c => c.r.name).render(StubDialect).onlySql.get
        assert(sql.startsWith("/* stub */ SELECT "), s"the interception must prepend its marker, got: $sql")
        assert(sql.contains("""FROM "row" "r""""), s"super must render the baseline FROM through the stub's identifier quoting, got: $sql")
    }

    "the limit clause override renders FETCH FIRST in place of the baseline LIMIT" in {
        val sql = Sql.from[Row]("r").limit(5).render(StubDialect).onlySql.get
        assert(sql.contains("FETCH FIRST 5 ROWS ONLY"), s"the clause override must render FETCH FIRST, got: $sql")
        assert(!sql.contains("LIMIT"), s"the baseline LIMIT must be replaced outright, got: $sql")
    }

    "StubBackend.open assembles a StubClient through the public Runtime.init, opening no socket" in {
        val url    = SqlConfig.Url.parse("stub://user:pw@localhost:1/db").getOrThrow
        val config = SqlConfig.default.copy(minConnections = 0)
        Abort.run[SqlException](Scope.run {
            new StubBackend().open(url, config).map { client =>
                Scope.ensure(client.close).andThen {
                    assert(
                        client.dialect.id == kyo.db.Idiom.Id("stub"),
                        s"the assembled client must carry the stub flavor, got ${client.dialect.id}"
                    )
                }
            }
        }).map {
            case Result.Success(_) => succeed
            case other             => fail(s"stub assembly must succeed with minConnections = 0 (no socket opens), got $other")
        }
    }

    "runtime discovery resolves a stub:// URL through the registration" in {
        // A non-literal URL, so resolution goes through Backend.Registry.current's discovery tier rather than the
        // compile-time literal check. The stub carries no services entry, it is register-only, so it reaches runtime
        // discovery through Backend.register: explicit on the JVM and Native (the ensure below), automatic at module
        // load on JS and Wasm. This is the cross-platform registration test of Section 18.4.
        TestBackendRegistration.ensure()
        val url: String = "stub://user:pw@localhost:1/db"
        Abort.run[SqlException](Scope.run {
            SqlClient.init(url, SqlConfig.default.copy(minConnections = 0)).map { client =>
                Scope.ensure(client.close).andThen {
                    assert(
                        client.dialect.id == kyo.db.Idiom.Id("stub"),
                        s"discovery must resolve the stub backend for a stub:// URL, got ${client.dialect.id}"
                    )
                }
            }
        }).map {
            case Result.Success(_) => succeed
            case other             => fail(s"discovery must resolve a stub:// URL through the registration, got $other")
        }
    }

    "the stub exception leaf carries SqlRetryable across the sealed boundary and matches its category" in {
        val ex: SqlException = StubUnavailableException("probe")
        assert(ex.isInstanceOf[SqlRetryable], "an out-of-tree leaf must be recoverable as SqlRetryable")
        assert(ex.isInstanceOf[SqlConnectionException], "the leaf must be an instance of exactly its category, keeping matching exhaustive")
        assert(ex.getMessage.contains("stub backend unavailable: probe"), s"got ${ex.getMessage}")
    }

end StubBackendTest
