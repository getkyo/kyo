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
    "typeNames sanitisation at init" - {
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
    }

end PostgresConfigTest
