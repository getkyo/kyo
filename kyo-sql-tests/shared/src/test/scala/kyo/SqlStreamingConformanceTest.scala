package kyo

import kyo.Sql.*

/** Cross-backend conformance for the two streaming surfaces: [[SqlClient.streamQuery]], which hands back raw rows, and the ambient
  * `fragment.stream`, which decodes each row as it arrives. Every backend that contributes a descriptor must stream back exactly the rows
  * that were inserted, decoded correctly and in insertion order, through either one.
  *
  * The row count is well past the default fetch batch size ([[SqlConfig.streamBatchSize]], 64), so the single leaf here also exercises the
  * multi-round-trip fetch path, not merely a case a lone `Execute` message could satisfy.
  *
  * The table uses `BIGINT` and `VARCHAR`, DDL types that read identically on every shipping engine, so the statement carries no
  * engine-specific branch at all.
  */
class SqlStreamingConformanceTest extends SqlBackendTest:

    private val rowCount = 150

    private case class StreamRow(id: Long, payload: String) derives SqlSchema, CanEqual

    private def expectedRows: Chunk[StreamRow] =
        Chunk.from((0 until rowCount).map(i => StreamRow(i.toLong, s"payload-$i")))

    forEachBackend() { (_, client, _) =>
        for
            _        <- client.executeRaw("CREATE TABLE streamrow (id BIGINT PRIMARY KEY, payload VARCHAR(256) NOT NULL)")
            _        <- Kyo.foreachDiscard(0 until rowCount)(i => Sql.insert[StreamRow].values(StreamRow(i.toLong, s"payload-$i")).run)
            raw      <- Scope.run(client.streamQuery(Sql.from[StreamRow]("r").orderBy(c => c.r.id.asc)).run)
            streamed <- Kyo.foreach(raw)(r => Abort.recover((e: SqlDecodeException) => Abort.fail(e: SqlException))(r.decode[StreamRow]))
            // The ambient form over the same rows: `fragment.stream` needs no client handle and decodes each row as
            // it arrives, so what a caller gets is `StreamRow` rather than a raw row to decode by hand. It is the one
            // run form that carries `Scope`, because the portal closes when the enclosing scope ends.
            decoded <- Scope.run(sql"SELECT id, payload FROM streamrow ORDER BY id".as[StreamRow].stream.run)
        yield
            assert(streamed.size == rowCount, s"expected $rowCount streamed rows, got ${streamed.size}")
            assert(streamed == expectedRows, s"expected every inserted row decoded in insertion order; got $streamed")
            assert(decoded == expectedRows, s"the ambient fragment stream must decode the same rows in the same order; got $decoded")
    }

end SqlStreamingConformanceTest
