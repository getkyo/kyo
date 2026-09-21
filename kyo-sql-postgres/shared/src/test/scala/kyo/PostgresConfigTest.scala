package kyo

import kyo.*
import kyo.Test

class PostgresConfigTest extends Test:

    "defaults" - {
        "PostgresConfig() is PostgresConfig.default" in {
            assert(PostgresConfig() == PostgresConfig.default)
        }

        "typeNames is empty" in {
            assert(PostgresConfig.default.typeNames.isEmpty)
        }

        "copyOutCleanupTimeout is 5 seconds" in {
            assert(PostgresConfig.default.copyOutCleanupTimeout == 5.seconds)
        }

        "searchPath is empty" in {
            assert(PostgresConfig.default.searchPath.isEmpty)
        }
    }

    /** What reaches the startup packet as `search_path`.
      *
      * The parameter is parsed as a list of identifiers rather than taken verbatim, so what is quoted decides which schema each entry
      * names, and an entry naming nothing has to be caught before the packet is written.
      */
    "searchPathValue" - {
        "names nothing when no schema was configured, so the server's own default stands" in {
            Abort.run[SqlException](PostgresConfig.default.searchPathValue).map(r => assert(r == Result.succeed(Absent)))
        }

        "joins the entries in the order they were given" in {
            Abort.run[SqlException](PostgresConfig(searchPath = Chunk("app", "public")).searchPathValue).map(r =>
                assert(r == Result.succeed(Present("app,public")))
            )
        }

        // Quoting it would name a literal schema called `$user` instead of the connecting role's own.
        "leaves the $user convention unquoted" in {
            Abort.run[SqlException](PostgresConfig(searchPath = Chunk("$user", "public")).searchPathValue).map(r =>
                assert(r == Result.succeed(Present("$user,public")))
            )
        }

        // Unquoted, the server folds `App` to `app` and the entry names a different schema than the one written.
        "quotes a name the parameter's own parse would not read back whole" in {
            Abort.run[SqlException](PostgresConfig(searchPath = Chunk("App", "My Schema")).searchPathValue).map(r =>
                assert(r == Result.succeed(Present("\"App\",\"My Schema\"")))
            )
        }

        "doubles an embedded quote rather than closing the name early" in {
            Abort.run[SqlException](PostgresConfig(searchPath = Chunk("we\"ird")).searchPathValue).map(r =>
                assert(r == Result.succeed(Present("\"we\"\"ird\"")))
            )
        }

        // Sent as-is this is a zero-length delimited identifier, which the server answers with a FATAL naming neither
        // the setting nor the entry. Refused here so the caller reads which value was wrong.
        "refuses an empty entry, naming the whole configured path" in {
            val path = Chunk("app", "")
            Abort.run[SqlException](PostgresConfig(searchPath = path).searchPathValue).map {
                case Result.Failure(e: SqlConnectionInvalidSearchPathException) => assert(e.searchPath == path)
                case other => fail(s"Expected Failure(SqlConnectionInvalidSearchPathException) but got $other")
            }
        }
    }

    "of" - {
        "reads back the attached instance" in {
            val pg = PostgresConfig(typeNames = Set("hstore"), copyOutCleanupTimeout = 10.seconds)
            assert(PostgresConfig.of(SqlConfig.default.extension(pg)) == pg)
        }

        "falls back to the defaults when no instance is attached" in {
            assert(PostgresConfig.of(SqlConfig.default) == PostgresConfig.default)
        }
    }

    // Type-name sanitisation is checked at SqlClient.init time. A fake URL is used so the error fires
    // before any network I/O (sanitisation runs before the URL config resolution and backend initialization).
    "settings sanitisation at init" - {
        "rejects a type name containing a single quote" in {
            val config = SqlConfig.default.extension(PostgresConfig(typeNames = Set("foo'bar")))
            Abort.run[SqlConnectionException](
                SqlClient.init("postgres://user:pass@127.0.0.1:9999/db", config)
            ).map {
                case Result.Failure(e: SqlConnectionInvalidTypeNameException) =>
                    assert(e.typeNames.contains("foo'bar"))
                    succeed
                case other =>
                    fail(s"Expected Failure(SqlConnectionInvalidTypeNameException) but got $other")
            }
        }

        "rejects a type name containing a backslash" in {
            val config = SqlConfig.default.extension(PostgresConfig(typeNames = Set("foo\\bar")))
            Abort.run[SqlConnectionException](
                SqlClient.init("postgres://user:pass@127.0.0.1:9999/db", config)
            ).map {
                case Result.Failure(e: SqlConnectionInvalidTypeNameException) =>
                    assert(e.typeNames.contains("foo\\bar"))
                    succeed
                case other =>
                    fail(s"Expected Failure(SqlConnectionInvalidTypeNameException) but got $other")
            }
        }

        // The address is a dead port, so reaching the typed refusal proves it happens before a socket is opened.
        "an empty search path entry refuses the init rather than the connection" in {
            val config = SqlConfig.default.extension(PostgresConfig(searchPath = Chunk("app", "")))
            Abort.run[SqlConnectionException](
                SqlClient.init("postgres://user:pass@127.0.0.1:9999/db", config)
            ).map {
                case Result.Failure(e: SqlConnectionInvalidSearchPathException) =>
                    assert(e.searchPath == Chunk("app", ""))
                    succeed
                case other =>
                    fail(s"Expected Failure(SqlConnectionInvalidSearchPathException) but got $other")
            }
        }
    }

end PostgresConfigTest
