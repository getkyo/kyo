package kyo

import kyo.SqlConfig.TlsMode

/** Unit tests for [[SqlConfig.Url.toConfig]], focused on which fields of the caller's [[SqlConfig]] survive the merge.
  *
  * A merge that copied a fixed subset of fields from `config` onto `url.toConfig` would silently reset seven public knobs to their defaults
  * (metricsEnabled, metricsScope, closeGrace, streamBatchSize, and the attached extensions). Each assertion below names one of them, so the
  * subset cannot narrow without a red leaf.
  */
class SqlConfigUrlToConfigTest extends SqlContainerTest:

    private val url = SqlConfig.Url.parse("postgres://alice:secret@localhost:5432/mydb").getOrThrow

    "preserves metricsEnabled = false" in {
        val cfg = SqlConfig.default.copy(metricsEnabled = false)
        Abort.run[SqlConnectionException](url.toConfig(cfg)).map {
            case Result.Success(merged) => assert(merged.metricsEnabled == false)
            case other                  => fail(s"toConfig failed unexpectedly: $other")
        }
    }

    "preserves custom metricsScope" in {
        val cfg = SqlConfig.default.copy(metricsScope = Present("myapp.db"))
        Abort.run[SqlConnectionException](url.toConfig(cfg)).map {
            case Result.Success(merged) => assert(merged.metricsScope == Present("myapp.db"))
            case other                  => fail(s"toConfig failed unexpectedly: $other")
        }
    }

    "preserves an attached PostgresConfig extension" in {
        val pg  = PostgresConfig(typeNames = Set("hstore"), copyOutCleanupTimeout = 11.seconds)
        val cfg = SqlConfig.default.extension(pg)
        Abort.run[SqlConnectionException](url.toConfig(cfg)).map {
            case Result.Success(merged) => assert(merged.extensionFor[PostgresConfig] == Present(pg))
            case other                  => fail(s"toConfig failed unexpectedly: $other")
        }
    }

    "preserves closeGrace and streamBatchSize" in {
        val cfg = SqlConfig.default.copy(
            closeGrace = 7.seconds,
            streamBatchSize = 512
        )
        Abort.run[SqlConnectionException](url.toConfig(cfg)).map {
            case Result.Success(merged) =>
                assert(merged.closeGrace == 7.seconds)
                assert(merged.streamBatchSize == 512)
            case other => fail(s"toConfig failed unexpectedly: $other")
        }
    }

    "URL tlsMode wins over user config" in {
        val explicitUrl = SqlConfig.Url.parse("postgres://alice:secret@localhost:5432/mydb?sslmode=disable").getOrThrow
        val cfg         = SqlConfig.default.copy(tlsMode = TlsMode.Require)
        Abort.run[SqlConnectionException](explicitUrl.toConfig(cfg)).map {
            case Result.Success(merged) =>
                assert(merged.tlsMode == TlsMode.Disable, s"URL sslmode=disable must win, got ${merged.tlsMode}")
            case other => fail(s"toConfig failed unexpectedly: $other")
        }
    }

    "a URL that declares no sslmode leaves the config's tlsMode alone" in {
        val cfg = SqlConfig.default.copy(tlsMode = TlsMode.Require)
        Abort.run[SqlConnectionException](url.toConfig(cfg)).map {
            case Result.Success(merged) =>
                assert(merged.tlsMode == TlsMode.Require, s"config tlsMode must survive a URL with no sslmode, got ${merged.tlsMode}")
                assert(merged.tls.isDefined, "Require must produce a TLS config so the mode is actually enforced")
            case other => fail(s"toConfig failed unexpectedly: $other")
        }
    }

    "a mode needing a CA certificate fails when neither the URL nor the config supplies one" in {
        val cfg = SqlConfig.default.copy(tlsMode = TlsMode.VerifyCa)
        Abort.run[SqlConnectionException](url.toConfig(cfg)).map {
            case Result.Failure(_: SqlConnectionTlsConfigException) => succeed
            case other => fail(s"Expected Failure(SqlConnectionTlsConfigException) but got: $other")
        }
    }

    "the config's caCertPath satisfies a verify mode the URL declared" in {
        val verifyUrl = SqlConfig.Url.parse("postgres://alice:secret@localhost:5432/mydb?sslmode=verify-ca").getOrThrow
        val cfg       = SqlConfig.default.copy(caCertPath = Present("/etc/ca.pem"))
        Abort.run[SqlConnectionException](verifyUrl.toConfig(cfg)).map {
            case Result.Success(merged) =>
                assert(merged.tlsMode == TlsMode.VerifyCa)
                assert(merged.caCertPath == Present("/etc/ca.pem"))
                assert(merged.tls.isDefined)
            case other => fail(s"toConfig failed unexpectedly: $other")
        }
    }

    // `SqlConfig.Url.toConfig` and this function implement one rule, and these leaves are what keeps them in
    // agreement: a second, independent implementation would have to make them disagree to exist.

    private val tlsUrls = Seq(
        "postgres://alice:secret@localhost:5432/mydb",
        "postgres://alice:secret@localhost:5432/mydb?sslmode=disable",
        "postgres://alice:secret@localhost:5432/mydb?sslmode=allow",
        "postgres://alice:secret@localhost:5432/mydb?sslmode=prefer",
        "postgres://alice:secret@localhost:5432/mydb?sslmode=require",
        "postgres://alice:secret@localhost:5432/mydb?sslmode=verify-ca&sslrootcert=/etc/ca.pem",
        "postgres://alice:secret@localhost:5432/mydb?sslmode=verify-full&sslrootcert=/etc/ca.pem"
    )

    "the no-argument toConfig is the default-config resolution, for every sslmode a URL can declare" in {
        Kyo.foreachDiscard(tlsUrls) { raw =>
            val parsed = SqlConfig.Url.parse(raw).getOrThrow
            Abort.run[SqlConnectionException](parsed.toConfig).map { direct =>
                Abort.run[SqlConnectionException](parsed.toConfig(SqlConfig.default)).map { merged =>
                    (direct, merged) match
                        case (Result.Success(d), Result.Success(m)) =>
                            assert(d.tlsMode == m.tlsMode, s"$raw: tlsMode ${d.tlsMode} vs ${m.tlsMode}")
                            assert(d.caCertPath == m.caCertPath, s"$raw: caCertPath ${d.caCertPath} vs ${m.caCertPath}")
                            assert(d.tls.isDefined == m.tls.isDefined, s"$raw: tls presence ${d.tls.isDefined} vs ${m.tls.isDefined}")
                        case other => fail(s"$raw: both paths must succeed, got $other")
                }
            }
        }
    }

    "toConfig fails on a verify mode with no certificate on both overloads" in {
        val verifyUrl = SqlConfig.Url.parse("postgres://alice:secret@localhost:5432/mydb?sslmode=verify-full").getOrThrow
        Abort.run[SqlConnectionException](verifyUrl.toConfig).map { direct =>
            Abort.run[SqlConnectionException](verifyUrl.toConfig(SqlConfig.default)).map { merged =>
                direct match
                    case Result.Failure(_: SqlConnectionTlsConfigException) => ()
                    case other => fail(s"toConfig must refuse verify-full with no certificate, got $other")
                merged match
                    case Result.Failure(_: SqlConnectionTlsConfigException) => succeed
                    case other => fail(s"toConfig must refuse verify-full with no certificate, got $other")
            }
        }
    }

    // The five scenarios below pin where a per-operation setting comes from. The URL points at a port nothing
    // listens on and minConnections is 0, so no connection is opened: what is under test is the resolution, not
    // any wire behavior.
    private val customConfig = SqlConfig.default.copy(
        maxConnections = 42,
        minConnections = 0,
        acquireTimeout = 17.seconds,
        queryTimeout = 33.seconds,
        metricsEnabled = false
    )

    private val unreachable = "postgres://alice:secret@localhost:9999/mydb"

    private def assertCustom(config: SqlConfig)(using kyo.test.AssertScope, Frame): Unit =
        assert(config.maxConnections == 42, "per-op reads must see the client's maxConnections")
        assert(config.acquireTimeout == 17.seconds, "per-op reads must see the client's acquireTimeout")
        assert(config.queryTimeout == 33.seconds, "per-op reads must see the client's queryTimeout")
        assert(config.metricsEnabled == false, "per-op reads must see the client's metricsEnabled")
    end assertCustom

    /** The settings in force for a DSL statement, which is what [[DB.withConfig]] adjusts and the `.run` surface threads into its lease. */
    private def inForceConfig(using Frame): SqlConfig < DB =
        DB.state.map(_.config)

    // Both settings doors are read, because the merged config has to arrive at each and they are reached
    // differently: `useConfig` answers with the client's own field for a caller holding the client, and the DB state's
    // config is what a statement written without a receiver reads. `DB.run(client)` seeds the second from the first,
    // so a merge that dropped a field fails at both rather than at whichever one a test happened to pick.
    "the merged config reaches per-op reads through DB.run" in {
        Abort.run[SqlException](Scope.run {
            SqlClient.initWith(unreachable, customConfig) { client =>
                DB.run(client) {
                    DB.client.map { installed =>
                        assert(installed eq client, "DB.run must install the client it was given")
                        client.useConfig(config => assertCustom(config)).andThen {
                            inForceConfig.map(config => assertCustom(config))
                        }
                    }
                }
            }
        }).map {
            case Result.Success(_) => succeed
            case Result.Failure(e) => fail(s"the body must run and complete without error: $e")
            case Result.Panic(t)   => fail(s"panicked: ${t.getMessage}")
        }
    }

    // A bare factory delegating to its *With sibling with `identity` would end that sibling's install as the call
    // returned, leaving the caller's statements under SqlConfig.default: a client built with maxConnections = 42
    // would pool 10. Sourcing per-op settings from the client's own field rather than from an ambient default is
    // what makes the bare factory correct, and no number of extra installs around the call substitutes for it.
    "a client from the bare init resolves per-op settings from its own merged config, outside any *With scope" in {
        Abort.run[SqlException](Scope.run {
            SqlClient.init(unreachable, customConfig).flatMap { client =>
                client.useConfig(config => assertCustom(config))
            }
        }).map {
            case Result.Success(_) => succeed
            case Result.Failure(e) => fail(s"init must complete without error: $e")
            case Result.Panic(t)   => fail(s"init panicked: ${t.getMessage}")
        }
    }

    // The base an adjustment narrows is the config the enclosing run installed, and `DB.run(client)` seeds that from
    // the client's own merged config. So the 41 settings the adjustment does not name keep that client's values
    // rather than reverting to SqlConfig.default's: a client built with maxConnections = 42 still pools 42 inside
    // the block. The adjustment resolves its base where the operation RUNS rather than where it was called;
    // resolving it at the call site would let narrowing queryTimeout on a client from a bare init also reset
    // maxConnections from 42 to 10.
    "withConfig narrows the setting it names and leaves the rest of the client's config alone" in {
        Abort.run[SqlException](Scope.run {
            SqlClient.init(unreachable, customConfig).flatMap { client =>
                DB.run(client) {
                    DB.withConfig(_.copy(queryTimeout = 1.second)) {
                        inForceConfig.map { config =>
                            assert(config.queryTimeout == 1.second, "withConfig must apply the setting it names")
                            assert(config.maxConnections == 42, "withConfig must leave the rest of the client's config alone")
                            assert(config.acquireTimeout == 17.seconds, "withConfig must leave the rest of the client's config alone")
                        }
                    }.andThen {
                        inForceConfig.map { config =>
                            assert(config.queryTimeout == 33.seconds, "the adjustment must not outlive the withConfig block")
                        }
                    }
                }
            }
        }).map {
            case Result.Success(_) => succeed
            case Result.Failure(e) => fail(s"withConfig must complete without error: $e")
            case Result.Panic(t)   => fail(s"withConfig panicked: ${t.getMessage}")
        }
    }

    "nested withConfig calls compose, and each pops independently" in {
        Abort.run[SqlException](Scope.run {
            SqlClient.init(unreachable, customConfig).flatMap { client =>
                DB.run(client) {
                    DB.withConfig(_.copy(queryTimeout = 1.second)) {
                        DB.withConfig(_.copy(maxConnections = 7)) {
                            inForceConfig.map { config =>
                                assert(config.queryTimeout == 1.second, "the outer adjustment must still apply")
                                assert(config.maxConnections == 7, "the inner adjustment must apply")
                            }
                        }.andThen {
                            inForceConfig.map { config =>
                                assert(config.maxConnections == 42, "the inner adjustment must not outlive its block")
                                assert(config.queryTimeout == 1.second, "the outer adjustment must survive the inner block")
                            }
                        }
                    }
                }
            }
        }).map {
            case Result.Success(_) => succeed
            case Result.Failure(e) => fail(s"nested withConfig must complete without error: $e")
            case Result.Panic(t)   => fail(s"nested withConfig panicked: ${t.getMessage}")
        }
    }

    // The client and the config in force are one state, so one adjustment and two clients cannot be in force at the
    // same moment. The boundary this leaf pins is that an inner run supplies a whole state, so it re-seeds the config
    // from its own client rather than layering the outer adjustment over it. Both clients' own settings are still each
    // other's control, which is why the leaf keeps two.
    "an adjustment belongs to the run it is inside, and a nested run re-seeds from its own client's config" in {
        val otherConfig = SqlConfig.default.copy(maxConnections = 5, minConnections = 0, queryTimeout = 9.seconds)
        Abort.run[SqlException](Scope.run {
            SqlClient.init(unreachable, customConfig).flatMap { first =>
                SqlClient.init(unreachable, otherConfig).flatMap { second =>
                    DB.run(first) {
                        DB.withConfig(_.copy(queryTimeout = 1.second)) {
                            inForceConfig.map { outer =>
                                assert(outer.queryTimeout == 1.second, "the adjustment must apply under the run it is inside")
                                assert(outer.maxConnections == 42, "the first client's own settings must be the base")
                            }.andThen {
                                DB.run(second) {
                                    inForceConfig.map { inner =>
                                        assert(inner.queryTimeout == 9.seconds, "an inner run must re-seed from its own client's config")
                                        assert(inner.maxConnections == 5, "the second client's own settings must be its base")
                                    }
                                }
                            }.andThen {
                                inForceConfig.map { restored =>
                                    assert(restored.queryTimeout == 1.second, "the outer adjustment must survive the inner run")
                                }
                            }
                        }
                    }
                }
            }
        }).map {
            case Result.Success(_) => succeed
            case Result.Failure(e) => fail(s"the adjustment must be scoped to its own run: $e")
            case Result.Panic(t)   => fail(s"the adjustment scenario panicked: ${t.getMessage}")
        }
    }

end SqlConfigUrlToConfigTest
