package kyo

/** A [[SqlRow.Codec]] for tests that need a row without a backend.
  *
  * Decodes by replaying `columns`, the same recorded-call vehicle [[kyo.SqlSchemaWriterMock]] produces, so a core test can build a row and
  * decode it without naming a backend codec. `offset` selects where in the recording the read starts, matching how a real codec skips to the
  * column a schema was asked to decode from.
  *
  * @param columns
  *   the recorded columns to replay, in row order
  */
final private[kyo] case class SqlRowCodecMock(columns: Chunk[SqlSchemaWriterMock.Call]) extends SqlRow.Codec:

    /** Replays the recording from `offset`, carrying the row's own column names into the reader.
      *
      * The names are what makes a multi-field derived row decodable by name here: the reader resolves each column to a field through the same
      * [[kyo.internal.SqlFieldMatcher]] both real codecs are handed, so a row whose columns arrive in an order the type did not declare still
      * lands each value in its own field. `naming` is the run-scope casing threaded from the query site, exactly as a backend codec threads it.
      */
    def read[A](schema: SqlSchema[A], row: SqlRow, offset: Int, naming: Maybe[SqlNaming], fieldMatch: SqlRow.FieldMatch)(using
        Frame
    ): A < Abort[SqlDecodeException] =
        val names = row.columnNames.drop(offset)
        SqlRow.Codec.catching(
            schema.read(
                new SqlSchemaReaderMock(
                    columns.drop(offset),
                    SqlSchemaWriterMock.postgres,
                    names,
                    matchesFieldAt = Maybe(kyo.internal.SqlFieldMatcher.of(schema.fieldNames, names, naming, fieldMatch))
                )
            )
        )
    end read

end SqlRowCodecMock

private[kyo] object SqlRowCodecMock:

    /** A codec that has no columns to replay, for a row whose decode path is never exercised. */
    val empty: SqlRowCodecMock = SqlRowCodecMock(Chunk.empty)

    /** Builds a row whose columns hold `values` and whose codec replays `recorded`.
      *
      * The raw bytes and the recorded calls are independent on purpose: a test asserting on [[SqlRow.column]] cares about the bytes, one
      * asserting on [[SqlRow.decode]] cares about the recording, and a few need both.
      */
    def row(names: Chunk[String], values: Chunk[Maybe[Span[Byte]]], recorded: Chunk[SqlSchemaWriterMock.Call]): SqlRow =
        new SqlRow(values, names.map(SqlRow.Column(_, 0)), SqlRowCodecMock(recorded))

    /** Builds a row of `recorded` columns whose raw bytes are absent, for tests that only decode. */
    def decodableRow(names: Chunk[String], recorded: Chunk[SqlSchemaWriterMock.Call]): SqlRow =
        row(names, names.map(_ => Maybe.Present(Span.empty[Byte])), recorded)

end SqlRowCodecMock
