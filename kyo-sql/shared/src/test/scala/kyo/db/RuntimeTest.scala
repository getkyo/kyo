package kyo.db

import kyo.*

/** Contract tests for [[Runtime]]: assembly via [[Runtime.init]] (merge, warm-up, the failure bracket), the dedicated-session door
  * [[Runtime.openDedicated]], the carrier's close state, and the policy split between the lease entries.
  */
class RuntimeTest extends Test:

    // Unsafe: test-only recorder state. Writes happen inside the computations under test; reads happen in their continuations, after the
    // recorded step completed.
    import AllowUnsafe.embrace.danger

    /** A session that records whether it was closed and otherwise answers every statement with an empty result. */
    final private class StubSession(val serial: Int) extends Connection:
        private val closedFlag = AtomicBoolean.Unsafe.init(false)

        /** Whether [[close]] or [[closeNow]] ran. */
        def closed: Boolean = closedFlag.get()

        def id: Long = serial.toLong

        def serverVersion(using Frame): Idiom.ServerVersion < (Async & Abort[SqlException]) =
            Sync.defer(Idiom.ServerVersion(1, 0, 0))

        def extendedQuery(sql: String, params: Chunk[Sql.BoundValue[?]])(using Frame): Chunk[SqlRow] < (Async & Abort[SqlException]) =
            Chunk.empty[SqlRow]

        def extendedExecute(sql: String, params: Chunk[Sql.BoundValue[?]])(using Frame): Long < (Async & Abort[SqlException]) =
            0L

        def extendedExecuteInsert(sql: String, params: Chunk[Sql.BoundValue[?]])(using
            Frame
        ): SqlClient.InsertOutcome < (Async & Abort[SqlException]) =
            SqlClient.InsertOutcome(0L, SqlClient.InsertOutcome.GeneratedKey.Unavailable)

        def simpleQuery(sql: String)(using Frame): Chunk[SqlRow] < (Async & Abort[SqlException]) =
            Chunk.empty[SqlRow]

        def simpleExecute(sql: String)(using Frame): Long < (Async & Abort[SqlException]) =
            0L

        def streamQuery(sql: String, params: Chunk[Sql.BoundValue[?]], batchSize: Int)(using
            Frame
        ): Stream[SqlRow, Async & Abort[SqlException] & Scope] =
            Stream.empty[SqlRow]

        def pipelined(stmts: Chunk[(String, Chunk[Sql.BoundValue[?]])])(using
            Frame
        ): Chunk[Result[SqlException, SqlClient.PipelineBuilder.Outcome]] < (Async & Abort[SqlException]) =
            Chunk.empty[Result[SqlException, SqlClient.PipelineBuilder.Outcome]]

        def beginTransaction(isolation: Maybe[SqlClient.IsolationLevel], readOnly: Boolean)(using
            Frame
        ): Unit < (Async & Abort[SqlException]) =
            ()

        def commitTransaction(using Frame): Unit < (Async & Abort[SqlException])   = ()
        def rollbackTransaction(using Frame): Unit < (Async & Abort[SqlException]) = ()

        def savepoint(name: String)(using Frame): Unit < (Async & Abort[SqlException])           = ()
        def releaseSavepoint(name: String)(using Frame): Unit < (Async & Abort[SqlException])    = ()
        def rollbackToSavepoint(name: String)(using Frame): Unit < (Async & Abort[SqlException]) = ()

        def ping(using Frame): Unit < (Async & Abort[SqlException])         = ()
        def resetSession(using Frame): Unit < (Async & Abort[SqlException]) = ()

        def acquireAdvisoryLock(key: Long, timeout: Maybe[Duration])(using Frame): Unit < (Async & Abort[SqlException]) = ()
        def releaseAdvisoryLock(key: Long)(using Frame): Unit < (Async & Abort[SqlException])                           = ()

        def inFlight(using AllowUnsafe): Boolean          = false
        def inOpenTransaction(using AllowUnsafe): Boolean = false

        def cancelInFlight(using Frame): Unit < (Async & Abort[SqlException])            = ()
        def rollbackIfOpenTransaction(using Frame): Unit < (Async & Abort[SqlException]) = ()
        def drainToIdle(using Frame): Boolean < (Async & Abort[SqlException])            = true

        def isOpen(using Frame): Boolean < Sync = Sync.Unsafe.defer(!closedFlag.get())
        def close(using Frame): Unit < Async    = Sync.Unsafe.defer(closedFlag.set(true))
        def closeNow(using Frame, AllowUnsafe): Unit =
            closedFlag.set(true)
    end StubSession

    /** A factory that records every call's arguments and every session it opened, failing typed from call `failingFrom + 1` onwards. */
    final private class StubFactory(failingFrom: Int = Int.MaxValue) extends Connection.Factory[StubSession]:
        private val callsRef  = AtomicRef.Unsafe.init(Chunk.empty[(SqlConfig.Address, Maybe[String], SqlConfig)])
        private val openedRef = AtomicRef.Unsafe.init(Chunk.empty[StubSession])

        def calls: Chunk[(SqlConfig.Address, Maybe[String], SqlConfig)] = callsRef.get()
        def opened: Chunk[StubSession]                                  = openedRef.get()
        def openCount: Int                                              = calls.size

        def open(address: SqlConfig.Address, password: Maybe[String], config: SqlConfig)(using
            Frame
        ): StubSession < (Async & Abort[SqlException]) =
            Sync.Unsafe.defer {
                val recorded = callsRef.updateAndGet(_.append((address, password, config)))
                if recorded.size > failingFrom then
                    Abort.fail[SqlException](SqlConnectionConnectFailedException(address.host, address.port, new Exception("stub refusal")))
                else
                    val session = new StubSession(recorded.size)
                    discard(openedRef.updateAndGet(_.append(session)))
                    session
                end if
            }
    end StubFactory

    private val address = SqlConfig.Address("stub", "localhost", 5432, "app", Present("alice"))

    private def urlOf(options: SqlConfig.Url.Options = SqlConfig.Url.Options.default): SqlConfig.Url =
        SqlConfig.Url(address, Present("secret"), options)

    // ── Runtime.init ──────────────────────────────────────────────────────────

    "init opens minConnections sessions before returning".timeout(15.seconds) in {
        val factory = new StubFactory
        Runtime.init(urlOf(), SqlConfig(minConnections = 2, maxConnections = 5), factory).map { _ =>
            assert(factory.openCount == 2)
        }
    }

    "init caps warm-up at maxConnections".timeout(15.seconds) in {
        val factory = new StubFactory
        Runtime.init(urlOf(), SqlConfig(minConnections = 7, maxConnections = 3), factory).map { _ =>
            assert(factory.openCount == 3)
        }
    }

    "init opens no session when minConnections is zero".timeout(15.seconds) in {
        val factory = new StubFactory
        Runtime.init(urlOf(), SqlConfig(minConnections = 0), factory).map { _ =>
            assert(factory.openCount == 0)
        }
    }

    // The merge runs first, so a URL whose TLS mode demands a CA certificate it did not name fails before any session is opened.
    "init fails typed when a TLS mode demands a certificate the URL did not name".timeout(15.seconds) in {
        val factory = new StubFactory
        val options = SqlConfig.Url.Options.default.copy(tlsMode = Present(SqlConfig.TlsMode.VerifyCa))
        Abort.run[SqlException](Runtime.init(urlOf(options), SqlConfig(minConnections = 1), factory)).map { outcome =>
            outcome match
                case Result.Failure(e) => assert(e.isInstanceOf[SqlConnectionException])
                case other             => fail("expected a typed SqlConnectionException failure, got: " + other)
            assert(factory.openCount == 0)
        }
    }

    // Warm-up runs behind a bracket that closes whatever it opened on any failure edge, so a partial warm-up leaves no session open.
    "init closes what it opened when warm-up fails".timeout(15.seconds) in {
        val factory = new StubFactory(failingFrom = 1)
        Abort.run[SqlException](Runtime.init(urlOf(), SqlConfig(minConnections = 2), factory)).map { outcome =>
            outcome match
                case Result.Failure(e) => assert(e.isInstanceOf[SqlConnectionConnectFailedException])
                case other             => fail("expected the factory's typed failure to propagate, got: " + other)
            assert(factory.opened.size == 1)
            assert(factory.opened.forall(_.closed))
        }
    }

    "init hands the factory the URL's address and credentials".timeout(15.seconds) in {
        val factory = new StubFactory
        Runtime.init(urlOf(), SqlConfig(minConnections = 1), factory).map { _ =>
            assert(factory.calls.size == 1)
            val (calledAddress, calledPassword, _) = factory.calls(0)
            assert(calledAddress == address)
            assert(calledPassword == Maybe("secret"))
        }
    }

    // The URL declares no options here, so the merged value carries the programmatic settings through to the warm-up opens.
    "init warms up under the merged settings".timeout(15.seconds) in {
        val factory = new StubFactory
        Runtime.init(urlOf(), SqlConfig(minConnections = 1, queryTimeout = 7.seconds), factory).map { _ =>
            assert(factory.calls.size == 1)
            val (_, _, calledConfig) = factory.calls(0)
            assert(calledConfig.queryTimeout == 7.seconds)
            assert(calledConfig.tlsMode == SqlConfig.TlsMode.Disable)
        }
    }

    // ── openDedicated ─────────────────────────────────────────────────────────

    // Two dedicated sessions coexist beyond maxConnections = 1, so neither occupied a pool slot.
    "openDedicated takes no pool slot".timeout(15.seconds) in {
        val factory = new StubFactory
        Runtime.init(urlOf(), SqlConfig(minConnections = 0, maxConnections = 1, acquireTimeout = 1.seconds), factory).map { runtime =>
            runtime.openDedicated.map { first =>
                runtime.openDedicated.map { second =>
                    assert(factory.opened.size == 2)
                    assert(!(first eq second))
                    assert(factory.opened.exists(_ eq first))
                    assert(factory.opened.exists(_ eq second))
                }
            }
        }
    }

    // Address, credentials and settings come from the carrier: the dedicated open carries the open-time merged value.
    "openDedicated opens under the carrier's open-time settings".timeout(15.seconds) in {
        val factory = new StubFactory
        Runtime.init(urlOf(), SqlConfig(minConnections = 0, queryTimeout = 9.seconds), factory).map { runtime =>
            runtime.openDedicated.map { _ =>
                assert(factory.calls.size == 1)
                val (calledAddress, calledPassword, calledConfig) = factory.calls(0)
                assert(calledAddress == address)
                assert(calledPassword == Maybe("secret"))
                assert(calledConfig.queryTimeout == 9.seconds)
            }
        }
    }

    "openDedicated fails once the carrier is closed".timeout(15.seconds) in {
        val factory = new StubFactory
        Runtime.init(urlOf(), SqlConfig(minConnections = 0), factory).map { runtime =>
            runtime.close(Duration.Zero).andThen(Abort.run[SqlException](runtime.openDedicated)).map { outcome =>
                outcome match
                    case Result.Failure(e) => assert(e.isInstanceOf[SqlConnectionPoolClosedException])
                    case other             => fail("expected SqlConnectionPoolClosedException, got: " + other)
            }
        }
    }

    // ── close and isClosed ────────────────────────────────────────────────────

    "close is idempotent and isClosed tracks it".timeout(15.seconds) in {
        val factory = new StubFactory
        Runtime.init(urlOf(), SqlConfig(minConnections = 0), factory).map { runtime =>
            runtime.isClosed.map { before =>
                runtime.close(Duration.Zero).andThen(runtime.isClosed).map { afterFirst =>
                    runtime.close(Duration.Zero).andThen(runtime.isClosed).map { afterSecond =>
                        assert(!before)
                        assert(afterFirst)
                        assert(afterSecond)
                    }
                }
            }
        }
    }

    "close closes the warmed sessions".timeout(15.seconds) in {
        val factory = new StubFactory
        Runtime.init(urlOf(), SqlConfig(minConnections = 2), factory).map { runtime =>
            runtime.close(Duration.Zero).map { _ =>
                assert(factory.opened.size == 2)
                assert(factory.opened.forall(_.closed))
            }
        }
    }

    // ── leaseStatement ────────────────────────────────────────────────────────

    "leaseStatement lends a pooled session and returns the body's value".timeout(15.seconds) in {
        val factory = new StubFactory
        Runtime.init(urlOf(), SqlConfig(minConnections = 1), factory).map { runtime =>
            runtime.leaseStatement(SqlConfig.default)(session => (session, 42)).map { case (session, value) =>
                assert(value == 42)
                assert(factory.opened.exists(_ eq session))
                assert(factory.openCount == 1)
            }
        }
    }

    // The default settings carry no retry schedule, so a failing body runs once and its typed failure propagates.
    "leaseStatement runs a failing body once with no schedule configured".timeout(15.seconds) in {
        val factory  = new StubFactory
        val attempts = AtomicInt.Unsafe.init(0)
        Runtime.init(urlOf(), SqlConfig(minConnections = 1), factory).map { runtime =>
            val leased = runtime.leaseStatement(SqlConfig.default) { _ =>
                Sync.Unsafe.defer {
                    discard(attempts.incrementAndGet())
                    Abort.fail[SqlException](SqlConnectionConnectFailedException("localhost", 5432, new Exception("transient")))
                }
            }
            Abort.run[SqlException](leased).map { outcome =>
                outcome match
                    case Result.Failure(e) => assert(e.isInstanceOf[SqlConnectionConnectFailedException])
                    case other             => fail("expected the body's typed failure to propagate, got: " + other)
                assert(attempts.get() == 1)
            }
        }
    }

    "leaseStatement bounds the body by the per-statement timeout".timeout(15.seconds) in {
        val factory = new StubFactory
        Runtime.init(urlOf(), SqlConfig(minConnections = 1), factory).map { runtime =>
            val leased = runtime.leaseStatement(SqlConfig(queryTimeout = 20.millis)) { _ =>
                Async.sleep(5.seconds).andThen(1)
            }
            Abort.run[SqlException](leased).map { outcome =>
                outcome match
                    case Result.Failure(e) => assert(e.isInstanceOf[SqlConnectionQueryTimeoutException])
                    case other             => fail("expected SqlConnectionQueryTimeoutException, got: " + other)
            }
        }
    }

    // ── lease ─────────────────────────────────────────────────────────────────

    // Retry is wrong on a pinned session: re-running the body would re-run work the first attempt may have committed.
    "lease runs a failing body exactly once even with a retry schedule".timeout(15.seconds) in {
        val factory  = new StubFactory
        val attempts = AtomicInt.Unsafe.init(0)
        Runtime.init(urlOf(), SqlConfig(minConnections = 1), factory).map { runtime =>
            val retrying = SqlConfig(retrySchedule = Present(Schedule.fixed(Duration.Zero).take(3)))
            val leased = runtime.lease(retrying) { _ =>
                Sync.Unsafe.defer {
                    discard(attempts.incrementAndGet())
                    Abort.fail[SqlException](SqlConnectionConnectFailedException("localhost", 5432, new Exception("transient")))
                }
            }
            Abort.run[SqlException](leased).map { outcome =>
                outcome match
                    case Result.Failure(e) => assert(e.isInstanceOf[SqlConnectionConnectFailedException])
                    case other             => fail("expected the body's typed failure to propagate, got: " + other)
                assert(attempts.get() == 1)
            }
        }
    }

    // The unit of work is the whole body, so the per-statement timeout does not bound it.
    "lease applies no per-statement timeout".timeout(15.seconds) in {
        val factory = new StubFactory
        Runtime.init(urlOf(), SqlConfig(minConnections = 1), factory).map { runtime =>
            runtime.lease(SqlConfig(queryTimeout = 10.millis)) { _ =>
                Async.sleep(150.millis).andThen(7)
            }.map { value =>
                assert(value == 7)
            }
        }
    }

    // The body runs once, so it may carry pending effects of its own.
    "lease carries the body's own pending effects".timeout(15.seconds) in {
        val factory = new StubFactory
        Runtime.init(urlOf(), SqlConfig(minConnections = 1), factory).map { runtime =>
            Var.run(10) {
                runtime.lease(SqlConfig.default)(_ => Var.update[Int](_ + 5))
            }.map { value =>
                assert(value == 15)
            }
        }
    }

    // ── leaseScoped ───────────────────────────────────────────────────────────

    "leaseScoped holds the session until the scope exits".timeout(15.seconds) in {
        val factory = new StubFactory
        Runtime.init(urlOf(), SqlConfig(minConnections = 1), factory).map { runtime =>
            Scope.run {
                runtime.leaseScoped(SqlConfig.default).map { session =>
                    assert(factory.opened.exists(_ eq session))
                    assert(!session.closed)
                }
            }.andThen(runtime.leaseStatement(SqlConfig.default)(_ => 1)).map { value =>
                assert(value == 1)
            }
        }
    }

end RuntimeTest
