package kyo

import kyo.*
import kyo.SqlConfig.TlsMode
import kyo.Test

/** Tests for the two halves of [[SqlConfig]] that carry backend-specific declarations: the [[SqlConfig.Extension]] mechanism, and the URL
  * query-string options a [[SqlConfig.Url]] parses into.
  *
  * The mechanism is per extension type, so exercising it needs two of them. [[PostgresConfig]] is one; the other is
  * [[SqlConfigExtensionTest.Probe]], declared here because a second real backend config would only duplicate what the mechanism already
  * does with one.
  */
class SqlConfigExtensionTest extends Test:

    "extension" - {
        "attaches an extension that extensionFor reads back" in {
            val pg     = PostgresConfig(typeNames = Set("hstore"))
            val config = SqlConfig.default.extension(pg)
            assert(config.extensionFor[PostgresConfig] == Present(pg))
        }

        "holds one extension per type, side by side" in {
            val pg     = PostgresConfig(typeNames = Set("hstore"))
            val probe  = SqlConfigExtensionTest.Probe("second")
            val config = SqlConfig.default.extension(pg).extension(probe)
            assert(config.extensions.size == 2)
            assert(config.extensionFor[PostgresConfig] == Present(pg))
            assert(config.extensionFor[SqlConfigExtensionTest.Probe] == Present(probe))
        }

        "replaces the extension already attached under the same type" in {
            val first  = PostgresConfig(typeNames = Set("hstore"))
            val second = PostgresConfig(typeNames = Set("geometry"))
            val config = SqlConfig.default.extension(first).extension(second)
            assert(config.extensions.size == 1)
            assert(config.extensionFor[PostgresConfig] == Present(second))
        }

        "leaves the config it was called on unchanged" in {
            val base     = SqlConfig.default
            val extended = base.extension(PostgresConfig(typeNames = Set("hstore")))
            assert(base.extensions.isEmpty)
            assert(extended.extensions.size == 1)
        }

        "extensionFor is Absent when no extension of that type is attached" in {
            val config = SqlConfig.default.extension(SqlConfigExtensionTest.Probe("only"))
            assert(config.extensionFor[PostgresConfig].isEmpty)
            assert(SqlConfig.default.extensionFor[SqlConfigExtensionTest.Probe].isEmpty)
        }

        "replacing one type leaves another type's extension in place" in {
            val probe = SqlConfigExtensionTest.Probe("keep me")
            val config = SqlConfig.default
                .extension(probe)
                .extension(PostgresConfig(typeNames = Set("hstore")))
                .extension(PostgresConfig(typeNames = Set("geometry")))
            assert(config.extensions.size == 2)
            assert(config.extensionFor[SqlConfigExtensionTest.Probe] == Present(probe))
            assert(config.extensionFor[PostgresConfig] == Present(PostgresConfig(typeNames = Set("geometry"))))
        }

        "survives copy" in {
            val pg     = PostgresConfig(typeNames = Set("hstore"))
            val config = SqlConfig.default.extension(pg).copy(maxConnections = 3)
            assert(config.maxConnections == 3)
            assert(config.extensionFor[PostgresConfig] == Present(pg))
        }
    }

    "Url.Options.parse" - {
        "an empty query string is Options.default" in {
            assert(SqlConfig.Url.Options.parse("").getOrThrow == SqlConfig.Url.Options.default)
        }

        "reads every claimed key" in {
            val opts = SqlConfig.Url.Options.parse(
                "application_name=reports&connectTimeout=5&socketTimeout=30&sslmode=verify-full&sslrootcert=/etc/ca.pem"
            ).getOrThrow
            assert(opts.applicationName == Present("reports"))
            assert(opts.connectTimeout == Present(5.seconds))
            assert(opts.socketTimeout == Present(30.seconds))
            assert(opts.tlsMode == Present(TlsMode.VerifyFull))
            assert(opts.sslRootCert == Present("/etc/ca.pem"))
            assert(opts.extra.isEmpty)
        }

        "keeps unclaimed pairs in extra" in {
            val opts = SqlConfig.Url.Options.parse("sslmode=require&currentSchema=reporting&flag").getOrThrow
            assert(opts.tlsMode == Present(TlsMode.Require))
            assert(opts.extra == Map("currentSchema" -> "reporting", "flag" -> ""))
        }

        "maps every sslmode value onto its TlsMode" in {
            val expected = List(
                "disable"     -> TlsMode.Disable,
                "allow"       -> TlsMode.Allow,
                "prefer"      -> TlsMode.Prefer,
                "require"     -> TlsMode.Require,
                "verify-ca"   -> TlsMode.VerifyCa,
                "verify-full" -> TlsMode.VerifyFull
            )
            expected.foreach { case (text, mode) =>
                assert(SqlConfig.Url.Options.parse(s"sslmode=$text").getOrThrow.tlsMode == Present(mode))
            }
        }

        "accepts an sslmode value in any case" in {
            assert(SqlConfig.Url.Options.parse("sslmode=VERIFY-FULL").getOrThrow.tlsMode == Present(TlsMode.VerifyFull))
        }

        "fails typed on an unrecognised sslmode value instead of falling back to plaintext" in {
            SqlConfig.Url.Options.parse("sslmode=banana") match
                case Result.Failure(e: SqlConnectionUrlOptionException) =>
                    assert(e.key == "sslmode")
                    assert(e.value == "banana")
                    assert(e.getMessage.contains("verify-full"), s"message must name the accepted values: ${e.getMessage}")
                case other =>
                    fail(s"Expected Failure(SqlConnectionUrlOptionException) but got: $other")
        }

        "reads a timeout of 0 as no timeout" in {
            val opts = SqlConfig.Url.Options.parse("connectTimeout=0&socketTimeout=0").getOrThrow
            assert(opts.connectTimeout == Present(Duration.Infinity))
            assert(opts.socketTimeout == Present(Duration.Infinity))
        }

        "reads a timeout carrying a duration unit" in {
            val opts = SqlConfig.Url.Options.parse("connectTimeout=30s&socketTimeout=2min").getOrThrow
            assert(opts.connectTimeout == Present(30.seconds))
            assert(opts.socketTimeout == Present(2.minutes))
        }

        "fails typed on a timeout that is not a whole number of seconds" in {
            SqlConfig.Url.Options.parse("connectTimeout=2.5") match
                case Result.Failure(e: SqlConnectionUrlOptionException) =>
                    assert(e.key == "connectTimeout")
                    assert(e.value == "2.5")
                case other =>
                    fail(s"Expected Failure(SqlConnectionUrlOptionException) but got: $other")
        }

        "fails typed on a negative timeout" in {
            SqlConfig.Url.Options.parse("socketTimeout=-1") match
                case Result.Failure(e: SqlConnectionUrlOptionException) =>
                    assert(e.key == "socketTimeout")
                    assert(e.value == "-1")
                case other =>
                    fail(s"Expected Failure(SqlConnectionUrlOptionException) but got: $other")
        }

        "declares its fields in the documented order" in {
            assert(
                SqlConfig.Url.Options.default.productElementNames.toList ==
                    List("applicationName", "connectTimeout", "socketTimeout", "tlsMode", "sslRootCert", "extra")
            )
        }
    }

    "Url.parse" - {
        "carries the parsed options" in {
            val url = SqlConfig.Url.parse("postgres://alice:secret@localhost:5432/mydb?sslmode=require&application_name=reports").getOrThrow
            assert(url.options.tlsMode == Present(TlsMode.Require))
            assert(url.options.applicationName == Present("reports"))
        }

        "propagates an option failure" in {
            SqlConfig.Url.parse("postgres://alice:secret@localhost:5432/mydb?sslmode=banana") match
                case Result.Failure(e: SqlConnectionUrlOptionException) => assert(e.key == "sslmode")
                case other => fail(s"Expected Failure(SqlConnectionUrlOptionException) but got: $other")
        }
    }

    "Url.toConfig" - {
        "carries the URL's TLS mode and root certificate into the config" in {
            val url =
                SqlConfig.Url.parse("postgres://alice:secret@localhost:5432/mydb?sslmode=verify-ca&sslrootcert=/etc/ca.pem").getOrThrow
            Abort.run[SqlConnectionException](url.toConfig).map {
                case Result.Success(config) =>
                    assert(config.tlsMode == TlsMode.VerifyCa)
                    assert(config.caCertPath == Present("/etc/ca.pem"))
                case other => fail(s"toConfig failed unexpectedly: $other")
            }
        }

        "defaults to Disable when the URL declares no sslmode" in {
            val url = SqlConfig.Url.parse("postgres://alice:secret@localhost:5432/mydb").getOrThrow
            Abort.run[SqlConnectionException](url.toConfig).map {
                case Result.Success(config) =>
                    assert(config.tlsMode == TlsMode.Disable)
                    assert(config.tls.isEmpty)
                case other => fail(s"toConfig failed unexpectedly: $other")
            }
        }
    }

    "builder methods set exactly their field" in {
        val sched  = Schedule.fixed(1.second)
        val tlsCfg = kyo.net.NetTlsConfig(caCertPath = Present("/tmp/ca.pem"))
        val built = SqlConfig.default
            .maxConnections(20)
            .minConnections(2)
            .acquireTimeout(5.seconds)
            .queryTimeout(30.seconds)
            .idleTimeout(2.minutes)
            .retrySchedule(sched)
            .tls(tlsCfg)
            .caCertPath("/tmp/ca.pem")
            .preparedStatementCacheSize(7)
            .preparedStatementTtl(1.minute)
            .cancelTimeout(3.seconds)
            .tlsMode(SqlConfig.TlsMode.Prefer)
            .metricsEnabled(false)
            .metricsScope("app")
            .connectionTestQuery("SELECT 1")
            .closeGrace(9.seconds)
            .streamBatchSize(256)
        val copied = SqlConfig.default.copy(
            maxConnections = 20,
            minConnections = 2,
            acquireTimeout = 5.seconds,
            queryTimeout = 30.seconds,
            idleTimeout = 2.minutes,
            retrySchedule = Present(sched),
            tls = Present(tlsCfg),
            caCertPath = Present("/tmp/ca.pem"),
            preparedStatementCacheSize = 7,
            preparedStatementTtl = 1.minute,
            cancelTimeout = 3.seconds,
            tlsMode = SqlConfig.TlsMode.Prefer,
            metricsEnabled = false,
            metricsScope = Present("app"),
            connectionTestQuery = Present("SELECT 1"),
            closeGrace = 9.seconds,
            streamBatchSize = 256
        )
        assert(built == copied)
    }

end SqlConfigExtensionTest

object SqlConfigExtensionTest:

    /** A second [[SqlConfig.Extension]] implementation, so the per-type behavior of `extension` and `extensionFor` has two types to
      * distinguish. Final, non-generic, and nested in a static object, which is what the mechanism's contract requires of any
      * implementation.
      */
    final case class Probe(label: String) extends SqlConfig.Extension derives CanEqual
end SqlConfigExtensionTest
