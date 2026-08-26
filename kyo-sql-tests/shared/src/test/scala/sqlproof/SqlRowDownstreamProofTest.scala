package sqlproof

import kyo.*

/** Proves the per-column metadata of a result row is reachable from outside the `kyo` package.
  *
  * A tool that runs SQL nobody typed needs, per column, a name and enough type information to render the value. The name was always
  * reachable and the type information was not: [[kyo.SqlRow.Column.typeToken]] existed, and the accessor that reached it was
  * `private[kyo]`, so a caller outside the package saw a name and bytes it could not interpret. This compiles from the public surface
  * alone, so a member creeping back behind `private[kyo]` would fail this compile.
  */
class SqlRowDownstreamProofTest extends kyo.Test:

    "a downstream caller reads a row's column metadata" in {
        val row = new SqlRow(
            Chunk(Maybe.Present(Span.from("hello".getBytes("UTF-8")))),
            Chunk(SqlRow.Column("greeting", 25)),
            new StubCodec
        )
        val described = row.columns.map(column => s"${column.name}/${column.typeToken}")
        assert(described == Chunk("greeting/25"))
        assert(row.columnKind(0) == SqlRow.ColumnKind.Unknown)
        assert(row.columnTypeName(0) == Absent)
        Abort.run[SqlDecodeException](row.text("greeting")).map { rendered =>
            assert(rendered == Result.Success(Maybe.Present("hello")))
        }
    }

    /** The smallest codec a downstream engine can supply: the metadata hooks carry defaults, so a codec that has none still compiles. */
    final class StubCodec extends SqlRow.Codec:
        def read[A](schema: SqlSchema[A], row: SqlRow, offset: Int, naming: Maybe[SqlNaming], fieldMatch: SqlRow.FieldMatch)(using
            Frame
        ): A < Abort[SqlDecodeException] =
            Abort.fail(SqlDecodeColumnNotFoundException("the stub codec decodes nothing"))
    end StubCodec

end SqlRowDownstreamProofTest
