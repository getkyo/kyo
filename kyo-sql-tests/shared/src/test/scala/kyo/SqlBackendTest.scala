package kyo

import kyo.internal.SqlTestBackend
import kyo.internal.SqlTestBackends

/** Base class for the backend-agnostic conformance suites: it runs a body once per available backend, mirroring kyo-pod's
  * `BasePodTest.runBackends`, over the DISCOVERED descriptor set rather than a hardcoded engine list.
  *
  * A conformance suite extends this and writes its behavior once through [[forEachBackend]]; the base opens the client, installs it as the
  * ambient [[DB]] client, and hands the body the backend descriptor, that client, and a fresh schema. It never names an engine: a body
  * branches on the descriptor's capability flags, and a leaf is named by the descriptor's own label.
  *
  * Container operations share one daemon, so leaves run sequentially.
  */
abstract class SqlBackendTest extends SqlContainerTest:

    override def timeout: Duration = 5.minutes

    /** Registers one leaf per available backend, each running `f` against that backend's opened client and fresh schema. Analogue of
      * `BasePodTest.runBackends`, with the leaf named by the descriptor's own label.
      *
      * When no backend is available it registers a single FAILING leaf rather than zero leaves, so a run with no reachable container is RED
      * rather than a green run with no coverage.
      */
    def forEachBackend(config: SqlConfig = SqlConfig())(
        f: (SqlTestBackend, SqlClient, SqlTestBackend.Schema) => kyo.test.AssertScope ?=> Unit < (Async & Abort[SqlException] & Scope & DB)
    )(using Frame): Unit =
        val backends = SqlTestBackends.available
        if backends.isEmpty then
            "no SQL backend available" in {
                fail(
                    "no SQL backend available: expected at least one registered test-backend descriptor with a reachable container runtime"
                )
            }
        else
            backends.foreach { backend =>
                s"[${backend.label}]" in { runOn(backend, config)(f) }
            }
        end if
    end forEachBackend

    private def runOn(backend: SqlTestBackend, config: SqlConfig)(
        f: (SqlTestBackend, SqlClient, SqlTestBackend.Schema) => kyo.test.AssertScope ?=> Unit < (Async & Abort[SqlException] & Scope & DB)
    )(using Frame, kyo.test.AssertScope) =
        Scope.run {
            backend.withFreshSchema { schema =>
                SqlClient.initUnscoped(schema.url, config).flatMap { client =>
                    Scope.ensure(client.close).andThen(DB.run(client)(f(backend, client, schema)))
                }
            }
        }

end SqlBackendTest
