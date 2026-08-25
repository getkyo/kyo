package kyo.internal

import kyo.<
import kyo.Abort
import kyo.Frame
import kyo.Maybe
import kyo.SqlCodec
import kyo.SqlCodec.Format
import kyo.SqlDecodeColumnNotFoundException
import kyo.SqlDecodeException
import kyo.SqlNaming
import kyo.SqlRow
import kyo.SqlSchema

/** A backend [[SqlRow.Codec]] that decodes a row's columns positionally through a backend reader.
  *
  * The decode itself, slicing the row to the codec's column span and running the codec's read against a fresh reader inside the row-level
  * abort, is the same for every backend. What differs is the reader the backend builds and the wire format it carries, which the two
  * abstract members below supply.
  */
abstract class SqlPositionalRowCodec extends SqlRow.Codec:

    /** The wire format of every column in the rows this codec decodes. */
    def format: Format

    /** Builds the backend's positional reader over `sliced`, handed the field matcher the codec and the sliced row resolved together.
      *
      * The one decode hook a backend implements, which is what makes this class the row-decode half of the backend SPI: an out-of-tree
      * engine supplies its [[kyo.SqlCodec.Reader]] here and inherits the slicing, field matching, and abort handling above unchanged.
      */
    def newReader(sliced: SqlRow, matchesFieldAt: Maybe[(Int, String) => Boolean])(using Frame): SqlCodec.Reader

    /** Decodes `schema` from the row's columns starting at `offset`.
      *
      * The reader is handed a field matcher built from the codec's field names and the sliced row together, because which field a column
      * belongs to depends on both: see [[kyo.internal.SqlFieldMatcher]] for the three modes and why the statement decides between them.
      * `naming` is the run-scope [[SqlNaming]] casing threaded from the query site (Absent for the raw [[SqlRow.decode]] API), so a cased
      * by-name read matches its columns. In the strict [[kyo.SqlRow.FieldMatch.ByName]] mode a field that resolves to no column fails
      * here, before any read, naming the missing column.
      */
    final def read[A](schema: SqlSchema[A], row: SqlRow, offset: Int, naming: Maybe[SqlNaming], fieldMatch: SqlRow.FieldMatch)(using
        Frame
    ): A < Abort[SqlDecodeException] =
        val sliced = row.slice(offset, offset + schema.width)
        val missing =
            fieldMatch match
                case SqlRow.FieldMatch.ByName => SqlFieldMatcher.missingByName(schema.fieldNames, sliced.columnNames, naming)
                case _                        => Maybe.empty[String]
        missing match
            case Maybe.Present(name) => Abort.fail(SqlDecodeColumnNotFoundException(name))
            case Maybe.Absent =>
                SqlRow.Codec.catching(
                    schema.read(newReader(sliced, Maybe(SqlFieldMatcher.of(schema.fieldNames, sliced.columnNames, naming, fieldMatch))))
                )
        end match
    end read

end SqlPositionalRowCodec
