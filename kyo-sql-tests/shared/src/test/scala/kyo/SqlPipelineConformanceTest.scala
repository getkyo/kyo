package kyo

import kyo.Sql.*

/** Cross-backend conformance for [[SqlClient.pipeline]]: a batch mixing statements that succeed with one that violates a constraint must
  * report the correct per-statement outcome for every backend that contributes a descriptor, the violating statement must surface as the
  * typed [[SqlServerConstraintViolationException]] rather than a panic, and the batch's successful statements must still have committed
  * their rows once the pipeline returns.
  *
  * This is the one cross-backend battery for `pipelined`; each engine's own integration suite covers that engine's protocol. The scenario
  * is the parametrized form of "mixed success, error, success".
  *
  * The table uses `BIGINT` and `VARCHAR`, DDL types that read identically on every shipping engine, so the statement carries no
  * engine-specific branch at all; the constraint violated is the PRIMARY KEY, standard SQL on both engines.
  */
class SqlPipelineConformanceTest extends SqlBackendTest:

    private case class PipelineRow(id: Long, payload: String) derives SqlSchema, CanEqual

    /** The id every backend is seeded with before the pipeline runs, so the pipeline's own attempt to insert the same id is the one
      * guaranteed to violate the PRIMARY KEY constraint, on either engine.
      */
    private val conflictingId = 3L

    forEachBackend() { (_, client, _) =>
        for
            _ <- client.executeRaw("CREATE TABLE pipelinerow (id BIGINT PRIMARY KEY, payload VARCHAR(64) NOT NULL)")
            _ <- Sql.insert[PipelineRow].values(PipelineRow(conflictingId, "pre-existing")).run
            results <- client.pipeline { p =>
                Kyo.foreachDiscard(1 to 5)(i => p.execute(Sql.insert[PipelineRow].values(PipelineRow(i.toLong, s"row$i"))))
            }
            raw     <- Scope.run(client.streamQuery(Sql.from[PipelineRow]("r").orderBy(c => c.r.id.asc)).run)
            decoded <- Kyo.foreach(raw)(r => Abort.recover((e: SqlDecodeException) => Abort.fail(e: SqlException))(r.decode[PipelineRow]))
        yield
            assert(results.size == 5, s"expected one outcome per registered statement, got ${results.size}")
            (1 to 5).foreach { i =>
                val outcome = results(i - 1)
                if i.toLong == conflictingId then
                    outcome match
                        case Result.Failure(e: SqlServerConstraintViolationException) =>
                            assert(
                                e.sqlState.startsWith("23"),
                                s"expected SQLSTATE class 23 (integrity constraint violation), got ${e.sqlState}"
                            )
                        case other =>
                            fail(
                                s"statement $i (duplicate primary key) must fail as a typed SqlServerConstraintViolationException, " +
                                    s"not a panic or a success; got $other"
                            )
                else
                    assert(outcome.isSuccess, s"statement $i must succeed, got $outcome")
                end if
            }
            val expectedRows = Chunk(
                PipelineRow(1L, "row1"),
                PipelineRow(2L, "row2"),
                PipelineRow(conflictingId, "pre-existing"),
                PipelineRow(4L, "row4"),
                PipelineRow(5L, "row5")
            )
            assert(
                decoded == expectedRows,
                s"the failed statement must not have replaced the pre-existing row and every succeeding statement must have committed; " +
                    s"expected $expectedRows, got $decoded"
            )
    }

end SqlPipelineConformanceTest
