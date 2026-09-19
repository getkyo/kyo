package kyo

import kyo.*
import kyo.internal.SqliteVfsDefault

/** The backend reached the way a caller reaches it: a `sqlite://` URL through `SqlClient.init`.
  *
  * Everything below this has its own suite. What this one proves is that the pieces meet: the scheme resolves to the backend, the backend
  * parses its own URL shape, the factory opens a connection, and rows come back decoded.
  */
class SqliteClientTest extends Test:

    /** One in-memory database, opened through a pool of exactly one connection. `:memory:` gives every CONNECTION its own private database,
      * so a single connection is what makes the name mean one database, and no leaf here needs two sessions.
      */
    private def withMemoryDb[A](f: String => A < (Async & Abort[SqlException] & Scope))(using Frame): A < (Async & Abort[SqlException]) =
        Scope.run(f("sqlite://:memory:"))

    "an empty string survives as a BOUND parameter" in {
        // The NOT NULL column is the assertion: a bind arriving as NULL is refused outright rather than stored wrongly.
        // Written with the query DSL because that is what puts a value on the bind path.
        withMemoryDb { url =>
            SqlClient.init(url, SqlConfig(maxConnections = 1)).map { client =>
                DB.run(client) {
                    for
                        _    <- client.executeRaw("CREATE TABLE bound_probe (id INTEGER PRIMARY KEY, v TEXT NOT NULL)")
                        _    <- client.execute(sql"INSERT INTO bound_probe VALUES (1, ${""})")
                        rows <- client.query("SELECT typeof(v), length(v) FROM bound_probe")
                        kind <- rows(0).decode[String](0)
                        size <- rows(0).decode[Long](1)
                    yield
                        assert(kind == "text", s"a bound empty string stored as $kind")
                        assert(size == 0L, s"length was $size")
                }
            }
        }
    }

    "an empty string binds as text rather than as null" in {
        // The literal path, which is a different one from the bind the sibling leaf covers.
        withMemoryDb { url =>
            SqlClient.init(url, SqlConfig(maxConnections = 1)).map { client =>
                DB.run(client) {
                    for
                        _    <- client.executeRaw("CREATE TABLE empty_probe (id INTEGER PRIMARY KEY, v TEXT NOT NULL)")
                        _    <- client.executeRaw("INSERT INTO empty_probe VALUES (1, '')")
                        rows <- client.query("SELECT typeof(v), length(v) FROM empty_probe")
                        kind <- rows(0).decode[String](0)
                        size <- rows(0).decode[Long](1)
                    yield
                        assert(kind == "text", s"an empty string stored as $kind")
                        assert(size == 0L, s"length was $size")
                }
            }
        }
    }

    "a named VFS reaches the engine, and an unknown one is refused" in {
        // A name the engine does not know must FAIL the open: falling back to the default silently would let a
        // browser page that asked for persistent storage appear to work and lose everything at the next reload. The
        // VFS that matters there is an OPFS one, which exists only in a browser, and the default is the same code
        // path. Its NAME is the operating system's rather than this platform's, so it is read rather than written
        // here: hard-coding `unix` fails on Windows, where SQLite calls the same VFS `win32`.
        val default = SqliteVfsDefault.name
        withMemoryDb { url =>
            val named   = SqlConfig(maxConnections = 1).extension(SqliteVfs(default))
            val unknown = SqlConfig(maxConnections = 1).extension(SqliteVfs("no-such-vfs"))
            // A lookup that missed would send the default VFS and make the refusal below untestable.
            assert(named.extensionFor[SqliteVfs] == Present(SqliteVfs(default)), s"${named.extensionFor[SqliteVfs]}")
            Scope.run(SqlClient.init(url, named).map(c => DB.run(c)(c.query("SELECT 1")))).map { rows =>
                assert(rows.size == 1, s"the named VFS did not open: $rows")
                Abort.run[SqlException](Scope.run(SqlClient.init(url, unknown).map(c => DB.run(c)(c.query("SELECT 1")))))
                    .map(result => assert(result.isFailure, s"an unknown VFS was accepted: $result"))
            }
        }
    }

    "a client opens on a sqlite URL and runs a statement" in {
        withMemoryDb { url =>
            SqlClient.init(url, SqlConfig(maxConnections = 1)).map { client =>
                DB.run(client) {
                    for
                        _    <- client.executeRaw("CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT)")
                        n    <- client.executeRaw("INSERT INTO t (name) VALUES ('alice')")
                        rows <- client.query("SELECT id, name FROM t")
                        name <- rows(0).decode[String](1)
                    yield
                        assert(n == 1L, s"the insert reported $n rows")
                        assert(rows.size == 1, s"expected one row, got ${rows.size}")
                        assert(name == "alice", s"decoded '$name'")
                }
            }
        }
    }

    "the in-memory name opens too, and is private to its connection" in {
        SqlClient.init("sqlite://:memory:", SqlConfig(maxConnections = 1)).map { client =>
            DB.run(client) {
                for
                    _    <- client.executeRaw("CREATE TABLE t (v INTEGER)")
                    _    <- client.executeRaw("INSERT INTO t VALUES (7)")
                    rows <- client.query("SELECT v FROM t")
                    v    <- rows(0).decode[Long](0)
                yield assert(v == 7L, s"read $v")
            }
        }
    }

    "a column's declared type reaches the caller as a neutral kind" in {
        withMemoryDb { url =>
            SqlClient.init(url, SqlConfig(maxConnections = 1)).map { client =>
                DB.run(client) {
                    for
                        _    <- client.executeRaw("CREATE TABLE t (n INTEGER, d 'DATE TEXT', x 'DECIMAL TEXT(38,10)')")
                        _    <- client.executeRaw("INSERT INTO t VALUES (1, '2026-08-25', '2.5')")
                        rows <- client.query("SELECT n, d, x, n + 0 FROM t")
                    yield
                        val row = rows(0)
                        assert(row.columnKind(0) == SqlRow.ColumnKind.Integer, s"n was ${row.columnKind(0)}")
                        // The multi-word declared name carries the KIND while forcing TEXT affinity.
                        assert(row.columnKind(1) == SqlRow.ColumnKind.Date, s"d was ${row.columnKind(1)}")
                        assert(row.columnKind(2) == SqlRow.ColumnKind.Decimal, s"x was ${row.columnKind(2)}")
                        // An expression is traceable to no declared column on any engine.
                        assert(row.columnKind(3) == SqlRow.ColumnKind.Unknown, s"n + 0 was ${row.columnKind(3)}")
                        assert(row.columnTypeName(2) == Present("DECIMAL TEXT(38,10)"), s"${row.columnTypeName(2)}")
                }
            }
        }
    }

    "a decimal keeps the scale its column declares, which SQLite does not apply" in {
        withMemoryDb { url =>
            SqlClient.init(url, SqlConfig(maxConnections = 1)).map { client =>
                DB.run(client) {
                    for
                        _        <- client.executeRaw("CREATE TABLE t (x 'DECIMAL TEXT(38,10)')")
                        _        <- client.executeRaw("INSERT INTO t VALUES ('2.5')")
                        rows     <- client.query("SELECT x FROM t")
                        rendered <- rows(0).text(0)
                    yield
                        // SQLite stored the three characters it was given, so the declared scale is the only place
                        // the intended one survives.
                        assert(rendered == Present("2.5000000000"), s"rendered $rendered")
                }
            }
        }
    }

    "a value past a double survives, because the column takes TEXT affinity" in {
        withMemoryDb { url =>
            SqlClient.init(url, SqlConfig(maxConnections = 1)).map { client =>
                DB.run(client) {
                    for
                        _        <- client.executeRaw("CREATE TABLE t (x 'DECIMAL TEXT(38,10)')")
                        _        <- client.executeRaw("INSERT INTO t VALUES ('98765432109876.543210')")
                        rows     <- client.query("SELECT x FROM t")
                        rendered <- rows(0).text(0)
                        typed    <- rows(0).decode[BigDecimal](0)
                    yield
                        // Under a bare DECIMAL(38,10) this reads back 98765432109876.547: NUMERIC affinity rewrites
                        // it to a double on the way in, losing six digits with nothing red.
                        assert(typed == BigDecimal("98765432109876.543210"), s"decoded $typed")
                        // The rendering pads to the DECLARED scale of 10 where the stored text carries 6, the codec
                        // applying a scale SQLite itself does not have.
                        assert(rendered == Present("98765432109876.5432100000"), s"rendered $rendered")
                }
            }
        }
    }

    "a statement string holding two statements is refused rather than half-run" in {
        withMemoryDb { url =>
            SqlClient.init(url, SqlConfig(maxConnections = 1)).map { client =>
                DB.run(client) {
                    for
                        _       <- client.executeRaw("CREATE TABLE t (v INTEGER)")
                        outcome <- Abort.run[SqlException](client.executeRaw("INSERT INTO t VALUES (1); DROP TABLE t"))
                        rows    <- client.query("SELECT count(*) FROM t")
                        count   <- rows(0).decode[Long](0)
                    yield
                        assert(outcome.isFailure, "a two-statement string must be refused")
                        // Neither statement ran: the refusal is before execution, not after the first one.
                        assert(count == 0L, s"the table holds $count rows, so part of it ran")
                }
            }
        }
    }

    "an unknown table is reported as such rather than as a generic error" in {
        withMemoryDb { url =>
            SqlClient.init(url, SqlConfig(maxConnections = 1)).map { client =>
                DB.run(client) {
                    Abort.run[SqlException](client.query("SELECT * FROM nope")).map { outcome =>
                        outcome match
                            case Result.Failure(e: SqlServerException) =>
                                // SQLite answers bare SQLITE_ERROR for this, for a syntax error and for an unknown
                                // column alike, so the message prefix is what separates them.
                                assert(e.sqlState == "42P01", s"SQLSTATE was ${e.sqlState}")
                            case other => fail(s"expected a typed server error, got $other")
                    }
                }
            }
        }
    }

    "a unique violation carries the integrity SQLSTATE" in {
        withMemoryDb { url =>
            SqlClient.init(url, SqlConfig(maxConnections = 1)).map { client =>
                DB.run(client) {
                    for
                        _       <- client.executeRaw("CREATE TABLE t (v INTEGER UNIQUE)")
                        _       <- client.executeRaw("INSERT INTO t VALUES (1)")
                        outcome <- Abort.run[SqlException](client.executeRaw("INSERT INTO t VALUES (1)"))
                    yield outcome match
                        case Result.Failure(e: SqlServerException) =>
                            assert(e.sqlState == "23505", s"SQLSTATE was ${e.sqlState}")
                        case other => fail(s"expected a typed server error, got $other")
                }
            }
        }
    }

    "an isolation level SQLite cannot honour is refused rather than silently ignored" in {
        withMemoryDb { url =>
            SqlClient.init(url, SqlConfig(maxConnections = 1)).map { client =>
                DB.run(client) {
                    Abort.run[SqlException] {
                        client.transaction(Present(SqlClient.IsolationLevel.ReadCommitted), readOnly = false) {
                            client.executeRaw("SELECT 1")
                        }
                    }.map { outcome =>
                        outcome match
                            case Result.Failure(_: SqliteIsolationLevelUnsupportedException) => succeed
                            case other                                                       =>
                                fail(s"READ COMMITTED must be refused rather than run at snapshot, got $other")
                    }
                }
            }
        }
    }

    "a transaction commits its write, and a rollback discards it" in {
        withMemoryDb { url =>
            SqlClient.init(url, SqlConfig(maxConnections = 1)).map { client =>
                DB.run(client) {
                    for
                        _ <- client.executeRaw("CREATE TABLE t (v INTEGER)")
                        _ <- client.transaction(client.executeRaw("INSERT INTO t VALUES (1)"))
                        _ <- Abort.run[SqlException] {
                            client.transaction {
                                client.executeRaw("INSERT INTO t VALUES (2)").andThen(
                                    Abort.fail(SqliteMultipleStatementsException("forced"))
                                )
                            }
                        }
                        rows  <- client.query("SELECT count(*) FROM t")
                        count <- rows(0).decode[Long](0)
                    yield assert(count == 1L, s"the rolled-back write survived: the table holds $count rows")
                }
            }
        }
    }

    "an advisory lock is refused, SQLite having none" in {
        withMemoryDb { url =>
            SqlClient.init(url, SqlConfig(maxConnections = 1)).map { client =>
                DB.run(client) {
                    Abort.run[SqlException](client.withAdvisoryLock(42L)(client.executeRaw("SELECT 1"))).map { outcome =>
                        outcome match
                            case Result.Failure(_: SqliteAdvisoryLockUnsupportedException) => succeed
                            case other                                                     => fail(s"expected a typed refusal, got $other")
                    }
                }
            }
        }
    }

    "the server version is the vendored library's" in {
        withMemoryDb { url =>
            SqlClient.init(url, SqlConfig(maxConnections = 1)).map { client =>
                DB.run(client) {
                    client.serverVersion.map { v =>
                        assert(v.major == 3 && v.minor == 53 && v.patch == 4, s"reported $v")
                    }
                }
            }
        }
    }

end SqliteClientTest
