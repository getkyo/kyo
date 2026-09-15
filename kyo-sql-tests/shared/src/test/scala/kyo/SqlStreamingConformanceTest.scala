package kyo

import kyo.Sql.*
import kyo.internal.SqlTestBackend.ColumnType

/** Cross-backend conformance for the two streaming surfaces: [[SqlClient.streamQuery]], which hands back raw rows, and the ambient
  * `fragment.stream`, which decodes each row as it arrives. Every backend that contributes a descriptor must stream back exactly the rows
  * that were inserted, decoded correctly and in insertion order, through either one.
  *
  * The row count is well past the default fetch batch size ([[SqlConfig.streamBatchSize]], 64), so the single leaf here also exercises the
  * multi-round-trip fetch path, not merely a case a lone `Execute` message could satisfy.
  *
  * The table's column types come from the descriptor like every other suite's. They happen to read identically on both shipping engines,
  * which is an argument for the DDL being safe and not an argument for writing it by hand: the rule is that a body names no engine's
  * spelling, and "these two agree today" is exactly the reasoning that leaves a third backend broken.
  */
class SqlStreamingConformanceTest extends SqlBackendTest:

    private val rowCount = 150

    private case class StreamRow(id: Long, payload: String) derives SqlSchema, CanEqual

    private def expectedRows: Chunk[StreamRow] =
        Chunk.from((0 until rowCount).map(i => StreamRow(i.toLong, s"payload-$i")))

    private def streamRowDdl(backend: kyo.internal.SqlTestBackend): String =
        s"CREATE TABLE streamrow (id ${backend.columnType(ColumnType.BigInt)} PRIMARY KEY, payload ${backend.textColumnType} NOT NULL)"

    /** Both streaming surfaces hand back every inserted row, decoded and in order, on every backend. */
    "both streaming surfaces yield every row, decoded and in order" - {
        forEachBackend() { (backend, client, _) =>
            for
                _   <- client.executeRaw(streamRowDdl(backend))
                _   <- Kyo.foreachDiscard(0 until rowCount)(i => Sql.insert[StreamRow].values(StreamRow(i.toLong, s"payload-$i")).run)
                raw <- Scope.run(client.streamQuery(Sql.from[StreamRow]("r").orderBy(c => c.r.id.asc)).run)
                streamed <-
                    Kyo.foreach(raw)(r => Abort.recover((e: SqlDecodeException) => Abort.fail(e: SqlException))(r.decode[StreamRow]))
                // The ambient form over the same rows: `fragment.stream` needs no client handle and decodes each row as
                // it arrives, so what a caller gets is `StreamRow` rather than a raw row to decode by hand. It is the one
                // run form that carries `Scope`, because the portal closes when the enclosing scope ends.
                decoded <- Scope.run(sql"SELECT id, payload FROM streamrow ORDER BY id".as[StreamRow].stream.run)
            yield
                assert(streamed.size == rowCount, s"${backend.label}: expected $rowCount streamed rows, got ${streamed.size}")
                assert(streamed == expectedRows, s"${backend.label}: expected every inserted row decoded in order; got $streamed")
                assert(decoded == expectedRows, s"${backend.label}: the ambient fragment stream must decode the same rows; got $decoded")
            end for
        }
    }

    /** A stream whose transaction has already committed still yields every row, on every backend.
      *
      * The transaction block returns the STREAM rather than the rows, which is easy to reach by accident and is what this pins. Be exact
      * about the mechanism, because the obvious reading is wrong: no cursor outlives the commit. A stream is a value, so the body constructs
      * it and returns without running it, and by the time it is consumed there is no enclosing transaction to bind to, so it leases a
      * connection of its own and runs whole. What the leaf guards is that laziness staying harmless, not a cursor surviving.
      *
      * The row count is past the fetch batch size on purpose, so the run really does make several round trips rather than one.
      *
      * The expected answer is pinned rather than merely compared, because the risk here is both engines moving together: a change to how a
      * stream holds its connection would show up as a smaller row count or an error on BOTH backends, which an agreement-only assertion
      * would pass.
      */
    "a stream consumed after its transaction committed yields every row" in {
        agreeAcrossBackends(expected = Present(s"the stream yielded $rowCount rows after its transaction committed")) {
            (backend, client, _) =>
                Scope.run {
                    for
                        _ <- client.executeRaw(streamRowDdl(backend))
                        _ <- Kyo.foreachDiscard(0 until rowCount)(i => Sql.insert[StreamRow].values(StreamRow(i.toLong, s"payload-$i")).run)
                        escaped <- client.transaction(client.streamQuery(Sql.from[StreamRow]("r").orderBy(c => c.r.id.asc)))
                        rows    <- escaped.run
                    yield s"the stream yielded ${rows.size} rows after its transaction committed"
                }
        }
    }

end SqlStreamingConformanceTest
