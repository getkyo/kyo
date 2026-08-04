package kyo

import kyo.Maybe.Absent
import kyo.db.Connection
import kyo.db.Idiom
import kyo.db.Runtime
import kyo.internal.client.SqlConnectionPool

/** Contract tests for the [[DB]] effect: the database a computation runs its statements against.
  *
  * Covers the promises the scaladoc makes: [[DB.client]] reads back the client the enclosing run supplied, [[DB.clientAs]] narrows to an
  * engine's own client type and fails with a typed [[SqlConnectionBackendMismatchException]] when the wrong engine is asked for, and the
  * three [[DB.run]] overloads differ only in the state they seed. The stub clients in the companion carry identity, a config, and the
  * test-scope stub dialect; nothing here executes a statement, so they never reach a server.
  */
class DBTest extends Test:

    override def timeout: Duration = 30.seconds

    /** The state the enclosing run installed, read through the Env bound the [[DB]] scaladoc documents. */
    private def currentState: DB.State < DB = Env.get[DB.State]

    private def stubConfig: SqlConfig = SqlConfig(metricsEnabled = false)

    "client" - {

        "answers with the same client instance run(client) supplied" in {
            val engine = DBTest.StubEngineClient(stubConfig)
            DB.run(engine)(DB.client).map { read =>
                assert(read eq engine)
            }
        }

        "under run(state), answers with the state's client" in {
            val engine = DBTest.StubEngineClient(stubConfig)
            val state  = DB.State(engine, SqlConfig(streamBatchSize = 7, metricsEnabled = false))
            DB.run(state)(DB.client).map { read =>
                assert(read eq engine)
            }
        }
    }

    "clientAs" - {

        "narrows to the engine client the run supplied, returning the same instance" in {
            val engine = DBTest.StubEngineClient(stubConfig)
            DB.run(engine)(DB.clientAs[DBTest.StubEngineClient]).map { narrowed =>
                val typed: DBTest.StubEngineClient = narrowed
                assert(typed eq engine)
            }
        }

        "asking for the wrong engine aborts with the typed backend mismatch" in {
            val engine = DBTest.StubEngineClient(stubConfig)
            Abort.run[SqlException](DB.run(engine)(DB.clientAs[DBTest.OtherEngineClient])).map { outcome =>
                outcome match
                    case Result.Failure(mismatch: SqlConnectionBackendMismatchException) =>
                        assert(mismatch.activeDriver == Idiom.Id("stub"))
                        assert(
                            mismatch.requested.contains("OtherEngineClient"),
                            "the failure must carry the type the caller asked for"
                        )
                    case other =>
                        fail(s"expected SqlConnectionBackendMismatchException, got $other")
            }
        }
    }

    "run" - {

        "run(client) seeds the client's own open-time config" in {
            val ownConfig = SqlConfig(streamBatchSize = 7, metricsEnabled = false)
            val engine    = DBTest.StubEngineClient(ownConfig)
            DB.run(engine)(currentState).map { state =>
                assert(state.client eq engine)
                assert(state.config == ownConfig)
            }
        }

        "run(client, config) makes the supplied config the whole answer, not a layer over the client's own" in {
            val ownConfig = SqlConfig(streamBatchSize = 7, maxConnections = 3, metricsEnabled = false)
            val supplied  = SqlConfig(maxConnections = 9, metricsEnabled = false)
            val engine    = DBTest.StubEngineClient(ownConfig)
            DB.run(engine, supplied)(currentState).map { state =>
                assert(state.client eq engine)
                assert(state.config == supplied)
                assert(state.config.streamBatchSize == 64, "the client's own streamBatchSize of 7 must be left unread")
                assert(engine.config == ownConfig, "the client's own config field keeps its meaning")
            }
        }

        "run(state) supplies exactly the state, the canonical overload" in {
            val engine  = DBTest.StubEngineClient(stubConfig)
            val inForce = SqlConfig(queryTimeout = 5.seconds, metricsEnabled = false)
            DB.run(DB.State(engine, inForce))(currentState).map { state =>
                assert(state.client eq engine)
                assert(state.config == inForce)
            }
        }

        "discharges DB and leaves the computation's remaining effects" in {
            val engine                         = DBTest.StubEngineClient(stubConfig)
            val v: SqlClient < (DB & Var[Int]) = Var.update[Int](_ + 1).andThen(DB.client)
            Var.runTuple(0)(DB.run(engine)(v)).map { result =>
                assert(result._1 == 1)
                assert(result._2 eq engine)
            }
        }

        "nested runs scope the state: the inner client inside, the outer restored after" in {
            val outer = DBTest.StubEngineClient(stubConfig)
            val inner = DBTest.OtherEngineClient(stubConfig)
            DB.run(outer) {
                DB.client.map { before =>
                    DB.run(inner)(DB.client).map { inside =>
                        DB.client.map { after =>
                            assert(before eq outer)
                            assert(inside eq inner)
                            assert(after eq outer)
                        }
                    }
                }
            }
        }
    }

    "the Env bound" - {

        "a computation carrying DB does not typecheck as effect-free" in {
            typeCheckFailure("""val direct: SqlClient < Any = DB.client""")
        }

        "DB widens to Env[DB.State], as the bound says" in {
            typeCheck("""summon[DB <:< Env[DB.State]]""")
        }

        "a fiber forked inside a run body sees the same client and the same config" in {
            val engine  = DBTest.StubEngineClient(stubConfig)
            val inForce = SqlConfig(streamBatchSize = 5, metricsEnabled = false)
            DB.run(DB.State(engine, inForce)) {
                Fiber.initUnscoped(currentState).map(_.get)
            }.map { seen =>
                assert(seen.client eq engine)
                assert(seen.config == inForce)
            }
        }
    }

end DBTest

object DBTest:

    private val stubUrl: SqlConfig.Url =
        SqlConfig.Url(
            SqlConfig.Address("stub", "stub-host", 1, "stubdb", Absent),
            Absent,
            SqlConfig.Url.Options.default
        )

    /** A factory no test reaches: DBTest opens no connection, so a lease against the stub pool is a defect, not a fixture feature. */
    private val refusingFactory: Connection.Factory[Connection] =
        new Connection.Factory[Connection]:
            def open(address: SqlConfig.Address, password: Maybe[String], config: SqlConfig)(using
                Frame
            ): Connection < (Async & Abort[SqlException]) =
                Abort.fail(SqlConnectionConnectFailedException(
                    address.host,
                    address.port,
                    new IllegalStateException("DBTest never opens a connection")
                ))

    /** Builds an inert carrier for a stub client: a refusing pool and fresh close/version state, assembled directly through the private[kyo]
      * [[kyo.db.Runtime]] constructor rather than [[kyo.db.Runtime.init]], since no test here opens a connection.
      */
    private def stubRuntime(config: SqlConfig)(using Frame): Runtime[Connection] =
        // Unsafe: test-only construction of the carrier's internal state. Nothing here reaches a server:
        // no DBTest scenario leases a connection, so the pool exists only to satisfy the carrier.
        import AllowUnsafe.embrace.danger
        val pool             = SqlConnectionPool.init[Connection](config, refusingFactory, Absent, summon[Frame])
        val closedRef        = AtomicBoolean.Unsafe.init(false).safe
        val serverVersionRef = AtomicRef.Unsafe.init(Maybe.empty[Idiom.ServerVersion]).safe
        new Runtime[Connection](stubUrl, config, pool, closedRef, serverVersionRef)
    end stubRuntime

    /** A [[SqlClient]] carrying identity, its carrier's `config`, and the test-scope stub dialect. Its carrier's pool is inert: nothing leases
      * from it.
      */
    sealed abstract class StubClient(cfg: SqlConfig) extends SqlClient(stubRuntime(cfg)):
        def dialect: Idiom = IdiomRenderStub
    end StubClient

    /** The engine the tests run on: what [[DB.clientAs]] narrows to when the ask matches. */
    final class StubEngineClient(config: SqlConfig) extends StubClient(config)

    /** A second engine type: asking for it on a [[StubEngineClient]] run is the backend mismatch [[DB.clientAs]] types. */
    final class OtherEngineClient(config: SqlConfig) extends StubClient(config)

end DBTest
