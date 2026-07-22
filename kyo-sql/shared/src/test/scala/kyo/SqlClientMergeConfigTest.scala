package kyo

import kyo.internal.postgres.types.EncodingRegistry
import kyo.internal.tls.TlsMode

/** Unit tests for [[SqlClient.mergeConfig]], focused on which fields of the caller's [[SqlConfig]] survive the merge.
  *
  * Regression: the previous implementation copied a fixed subset of fields from `config` onto `url.toConfig`, silently resetting eight
  * public knobs to their defaults (metricsEnabled, encodingRegistry, cancelTimeout, closeGrace, streamBatchSize, copyOutCleanupTimeout,
  * metricsScope). Each of the assertions below would have failed on that implementation.
  */
class SqlClientMergeConfigTest extends Test:

    private val url = SqlConfig.Url.parse("postgres://alice:secret@localhost:5432/mydb").getOrThrow

    "preserves metricsEnabled = false" in {
        val cfg = SqlConfig.default.copy(metricsEnabled = false)
        Abort.run[SqlConnectionException](SqlClient.mergeConfig(url, cfg)).map {
            case Result.Success(merged) => assert(merged.metricsEnabled == false)
            case other                  => fail(s"mergeConfig failed unexpectedly: $other")
        }
    }

    "preserves custom metricsScope" in {
        val cfg = SqlConfig.default.copy(metricsScope = Present("myapp.db"))
        Abort.run[SqlConnectionException](SqlClient.mergeConfig(url, cfg)).map {
            case Result.Success(merged) => assert(merged.metricsScope == Present("myapp.db"))
            case other                  => fail(s"mergeConfig failed unexpectedly: $other")
        }
    }

    "preserves custom encodingRegistry (identity)" in {
        val custom: EncodingRegistry = EncodingRegistry.empty
        val cfg                      = SqlConfig.default.copy(encodingRegistry = custom)
        Abort.run[SqlConnectionException](SqlClient.mergeConfig(url, cfg)).map {
            case Result.Success(merged) => assert(merged.encodingRegistry eq custom)
            case other                  => fail(s"mergeConfig failed unexpectedly: $other")
        }
    }

    "preserves cancelTimeout" in {
        val cfg = SqlConfig.default.copy(cancelTimeout = 15.seconds)
        Abort.run[SqlConnectionException](SqlClient.mergeConfig(url, cfg)).map {
            case Result.Success(merged) => assert(merged.cancelTimeout == 15.seconds)
            case other                  => fail(s"mergeConfig failed unexpectedly: $other")
        }
    }

    "preserves closeGrace, streamBatchSize, copyOutCleanupTimeout" in {
        val cfg = SqlConfig.default.copy(
            closeGrace = 7.seconds,
            streamBatchSize = 512,
            copyOutCleanupTimeout = 11.seconds
        )
        Abort.run[SqlConnectionException](SqlClient.mergeConfig(url, cfg)).map {
            case Result.Success(merged) =>
                assert(merged.closeGrace == 7.seconds)
                assert(merged.streamBatchSize == 512)
                assert(merged.copyOutCleanupTimeout == 11.seconds)
            case other => fail(s"mergeConfig failed unexpectedly: $other")
        }
    }

    "URL tlsMode wins over user config" in {
        val explicitUrl = SqlConfig.Url.parse("postgres://alice:secret@localhost:5432/mydb?sslmode=disable").getOrThrow
        val cfg         = SqlConfig.default.copy(tlsMode = TlsMode.Require)
        Abort.run[SqlConnectionException](SqlClient.mergeConfig(explicitUrl, cfg)).map {
            case Result.Success(merged) =>
                assert(merged.tlsMode == TlsMode.Disable, s"URL sslmode=disable must win, got ${merged.tlsMode}")
            case other => fail(s"mergeConfig failed unexpectedly: $other")
        }
    }

end SqlClientMergeConfigTest
