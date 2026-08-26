package kyo

import kyo.internal.SqlTestBackend

/** Both arms of where a [[SqlNaming]] given has to be declared to reach a query, against a live server.
  *
  * A casing given is found by ordinary implicit search at the call site of the run, so declaring it somewhere the run is not (a companion
  * object, another method) leaves the decode asking for the verbatim Scala field names against snake_case columns. That is not detectable
  * while compiling: whether the columns are cased is a fact about the database, so both arms below run the same query against the same
  * table and differ only in whether the given is in scope.
  *
  * The failing arm is the one this exists for. It used to report only that a column was missing, which named neither the columns the row
  * did have nor the reason they looked different, and the same failure reached the caller from inside a workflow store where it presented
  * as a silent engine stall. It now lists the row's own columns and says that a casing given is resolved at the call site.
  */
class SqlNamingScopeConformanceTest extends SqlBackendTest:

    case class ExecutionRow(executionId: String, flowId: String) derives CanEqual

    private def createExecutions(backend: SqlTestBackend, client: SqlClient)(using Frame): Unit < (Async & Abort[SqlException]) =
        client.executeRaw(
            s"CREATE TABLE flow_execution (execution_id ${backend.textColumnType}, flow_id ${backend.textColumnType})"
        ).andThen(
            client.executeRaw("INSERT INTO flow_execution VALUES ('exec-1', 'fulfillment')")
        ).unit

    "a casing given in scope at the run site is applied" - {
        forEachBackend() { (backend, client, _) =>
            given SqlNaming = SqlNaming.SnakeCase
            for
                _    <- createExecutions(backend, client)
                rows <- sql"SELECT execution_id, flow_id FROM flow_execution".as[ExecutionRow].run
            yield assert(
                rows == Chunk(ExecutionRow("exec-1", "fulfillment")),
                s"the given is in scope here, so the cased columns must decode, got $rows"
            )
            end for
        }
    }

    "a casing given that does not reach the run site fails naming the row's columns and the casing" - {
        forEachBackend() { (backend, client, _) =>
            // No `given SqlNaming` in scope at this run, which is what a given declared in a companion object of some
            // other class looks like from here.
            for
                _      <- createExecutions(backend, client)
                result <- Abort.run[SqlException](sql"SELECT execution_id, flow_id FROM flow_execution".as[ExecutionRow].run)
            yield result match
                case Result.Failure(e: SqlDecodeColumnNotFoundException) =>
                    assert(e.columnName == "executionId", s"the lookup asks for the verbatim field name, got ${e.columnName}")
                    assert(
                        e.availableColumns.contains("execution_id"),
                        s"the failure must list the row's own columns, got ${e.availableColumns}"
                    )
                    assert(e.message.contains("only in casing"), s"the failure must name the casing, got: ${e.message}")
                    assert(e.message.contains("SqlNaming"), s"the failure must name the given, got: ${e.message}")
                case other =>
                    assert(false, s"expected the decode to fail naming the missing column, got $other")
        }
    }

end SqlNamingScopeConformanceTest
