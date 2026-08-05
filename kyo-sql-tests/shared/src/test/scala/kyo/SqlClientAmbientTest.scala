package kyo
import kyo.Test
import kyo.db.Idiom

/** Pins which entry points install the ambient client (the [[DB]] effect) and which do not.
  *
  * Exactly one thing installs a client: [[DB.run]], in its client-taking and URL-taking overloads. Every factory, bare and `*With` alike,
  * only produces the client. The `*With` variants hand it to their callback and install nothing, so a callback that reads the ambient
  * client without its own [[DB.run]] leaves `DB` in the row for the caller to supply. A computation that never supplied one is a compile
  * error rather than a runtime failure, so the "no client installed" case is a type property (see [[DBTest]]) and the negative half below
  * is a compile probe.
  *
  * Every scenario uses a port nothing listens on with `minConnections = 0`, so no connection is opened and what is under test is the
  * installation, not any wire behavior.
  */
class SqlClientAmbientTest extends SqlContainerTest:

    private val pgUrl = "postgres://alice:secret@localhost:9999/mydb"
    private val myUrl = "mysql://alice:secret@localhost:9998/mydb"

    private val config: SqlConfig = SqlConfig.default.copy(minConnections = 0)

    /** Asserts the [[DB]] effect's client is exactly `client`, from inside the run that installed it. */
    private def assertInstalled(client: SqlClient)(using kyo.test.AssertScope, Frame): Unit < (Abort[SqlException] & DB) =
        DB.client.map { ambient =>
            assert(ambient eq client, "the installed client must be the one DB.run was given")
        }

    "DB.run installs a client that DB.client reads back" in {
        Abort.run[SqlException](Scope.run {
            SqlClient.init(pgUrl, config).flatMap { client =>
                DB.run(client)(assertInstalled(client))
            }
        }).map {
            case Result.Success(_) => succeed
            case other             => fail(s"DB.run/DB.client round trip must succeed, got $other")
        }
    }

    "the ambient client pops back to the outer one when a nested run exits" in {
        Abort.run[SqlException](Scope.run {
            SqlClient.init(pgUrl, config).flatMap { outer =>
                SqlClient.init(pgUrl, config).flatMap { inner =>
                    DB.run(outer) {
                        DB.run(inner)(assertInstalled(inner)).andThen(assertInstalled(outer))
                    }
                }
            }
        }).map {
            case Result.Success(_) => succeed
            case other             => fail(s"a nested run must restore the outer client, got $other")
        }
    }

    "DB.run(url) opens a client and installs it for the computation" in {
        Abort.run[SqlException](Scope.run {
            DB.run(pgUrl, config) {
                DB.client.map { ambient =>
                    assert(
                        ambient.address.port == 9999,
                        s"DB.run(url) must install the client it opened for the URL, got port ${ambient.address.port}"
                    )
                }
            }
        }).map {
            case Result.Success(_) => succeed
            case other             => fail(s"DB.run(url) must open and install in one step, got $other")
        }
    }

    // One leaf per `*With` variant: each hands its client to the callback without installing anything, so the
    // callback proves the handover by running DB.run itself with the client it was given.

    "SqlClient.initWith hands its client to the callback without installing it" in {
        Abort.run[SqlException](Scope.run {
            SqlClient.initWith(pgUrl, config)(client => DB.run(client)(assertInstalled(client)))
        }).map {
            case Result.Success(_) => succeed
            case other             => fail(s"initWith must hand over a client DB.run can install, got $other")
        }
    }

    "SqlClient.initUnscopedWith hands its client to the callback without installing it" in {
        Abort.run[SqlException](
            SqlClient.initUnscopedWith(pgUrl, config) { client =>
                DB.run(client)(assertInstalled(client)).andThen(client.close)
            }
        ).map {
            case Result.Success(_) => succeed
            case other             => fail(s"initUnscopedWith must hand over a client DB.run can install, got $other")
        }
    }

    "PostgresClient.initWith hands its client to the callback without installing it" in {
        Abort.run[SqlException](Scope.run {
            PostgresClient.initWith(pgUrl, config)(client => DB.run(client)(assertInstalled(client)))
        }).map {
            case Result.Success(_) => succeed
            case other             => fail(s"PostgresClient.initWith must hand over a client DB.run can install, got $other")
        }
    }

    "PostgresClient.initUnscopedWith hands its client to the callback without installing it" in {
        Abort.run[SqlException](
            PostgresClient.initUnscopedWith(pgUrl, config) { client =>
                DB.run(client)(assertInstalled(client)).andThen(client.close)
            }
        ).map {
            case Result.Success(_) => succeed
            case other             => fail(s"PostgresClient.initUnscopedWith must hand over a client DB.run can install, got $other")
        }
    }

    "MysqlClient.initWith hands its client to the callback without installing it" in {
        Abort.run[SqlException](Scope.run {
            MysqlClient.initWith(myUrl, config)(client => DB.run(client)(assertInstalled(client)))
        }).map {
            case Result.Success(_) => succeed
            case other             => fail(s"MysqlClient.initWith must hand over a client DB.run can install, got $other")
        }
    }

    "MysqlClient.initUnscopedWith hands its client to the callback without installing it" in {
        Abort.run[SqlException](
            MysqlClient.initUnscopedWith(myUrl, config) { client =>
                DB.run(client)(assertInstalled(client)).andThen(client.close)
            }
        ).map {
            case Result.Success(_) => succeed
            case other             => fail(s"MysqlClient.initUnscopedWith must hand over a client DB.run can install, got $other")
        }
    }

    "a With callback does not install: an ambient read inside it keeps DB in the row" in {
        // The declared result row has no DB, so if initWith installed its client the snippet would compile.
        // It must not: the callback's ambient read leaves DB for a caller-side DB.run to supply.
        typeCheckFailure(
            """val probe: SqlClient < (Async & Scope & Abort[SqlException]) =
  SqlClient.initWith("postgres://u:p@localhost:5432/db")(_ => DB.client)"""
        )
        typeCheck(
            """val probe: SqlClient < (Async & Scope & Abort[SqlException] & DB) =
  SqlClient.initWith("postgres://u:p@localhost:5432/db")(_ => DB.client)"""
        )
    }

    // The counterpart to the scenarios above, and the reason per-operation settings are resolved from the client
    // rather than from the effect's state: a bare factory has nowhere to install a client that outlives its own call,
    // so it hands the client back and the caller decides where to supply it. SqlClientMergeConfigTest pins that this
    // client's own settings still reach its operations.
    "a bare factory hands its client back rather than installing it" in {
        Abort.run[SqlException](Scope.run {
            SqlClient.init(pgUrl, config).map(_.address)
        }).map {
            case Result.Success(address) =>
                assert(address.host == "localhost", s"the returned client must name the URL's host, got '${address.host}'")
                assert(address.port == 9999, s"the returned client must name the URL's port, got ${address.port}")
            case other => fail(s"the bare init must answer with a client, got $other")
        }
    }

    // The other half of that asymmetry, and a compile-time property now rather than a run-time one: reading the
    // installed client is a `DB` operation, so a read that no run supplied a client to does not compile. Both
    // directions are checked, which is what distinguishes a snippet rejected for the missing `DB` from one rejected
    // for an unrelated reason. DBTest carries the same property for a statement rather than for a companion read.
    "an ambient read outside an install does not compile" in {
        typeCheckFailure("""val ambient: SqlConfig.Address < (Async & Abort[SqlException]) = DB.address""")
        typeCheck("""val ambient: SqlConfig.Address < DB = DB.address""")
    }

    "the concrete factories return the concrete class, so engine-only members need no cast" in {
        Abort.run[SqlException](Scope.run {
            PostgresClient.init(pgUrl, config).flatMap { pg =>
                MysqlClient.init(myUrl, config).map { my =>
                    assert(pg.dialect.id == Idiom.Id("postgres"), s"expected the postgres dialect, got ${pg.dialect.id}")
                    assert(my.dialect.id == Idiom.Id("mysql"), s"expected the mysql dialect, got ${my.dialect.id}")
                    // The widening itself is a compile-time property: the ascription below does not compile unless a
                    // PostgresClient IS a SqlClient, and comparing the result to `pg` could only ever be true. What a
                    // run CAN see is that nothing was converted on the way, so the widened reference is asked for the
                    // engine-specific answer instead.
                    val widened: SqlClient = pg
                    assert(
                        widened.dialect.id == Idiom.Id("postgres"),
                        s"widening must not convert the client, got ${widened.dialect.id}"
                    )
                }
            }
        }).map {
            case Result.Success(_) => succeed
            case other             => fail(s"the concrete factories must produce their own class, got $other")
        }
    }

end SqlClientAmbientTest
