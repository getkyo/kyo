package kyo.internal

import kyo.Chunk
import kyo.Frame
import kyo.Maybe
import kyo.Span
import kyo.SqlCodec
import kyo.SqlDecodeColumnAbsentException
import kyo.SqlRow
import kyo.SqlSchema

/** The positional core a backend row reader shares: the column cursor, the raw column read, and the field-slot resolution the
  * [[kyo.SqlSchema]] row read drives.
  *
  * A backend [[SqlCodec.Reader]] maps each primitive and vocabulary read to a column read positionally from a [[SqlRow]]. The cursor walk
  * and the raw byte access are the same for every backend and live here; the per-column value decode is supplied by the backend subclass.
  *
  * @param row
  *   the SQL result row to read from
  * @param matchesFieldAt
  *   decides which row field the column at a given index belongs to, built by the codec from the row codec's field names and the row's
  *   column names. See [[SqlFieldMatcher]]. `Absent` when the caller has no row codec in hand (a single-column read, which never drives
  *   the field loop), leaving the probe compared against the column name verbatim.
  * @param readerFrame
  *   call-site frame attached to any decode errors
  */
abstract class SqlPositionalRowReader(row: SqlRow, matchesFieldAt: Maybe[(Int, String) => Boolean], readerFrame: Frame)
    extends SqlCodec.Reader(readerFrame):

    // Not thread-safe by design: one reader per row decode, never shared.
    private[kyo] var idx = 0

    /** Returns the raw bytes for the current column and advances the cursor, or throws if the column is NULL. */
    private[kyo] def nextBytes(): Span[Byte] =
        val column = row.column(idx)
        idx += 1
        column.getOrElse(throw SqlDecodeColumnAbsentException(idx - 1)(using frame))
    end nextBytes

    /** The backend type token for the column the cursor is on, read before [[nextBytes]] advances past it: a PostgreSQL OID, a MySQL column
      * token. Each backend supplies its own unspecified fallback for a row assembled without server column metadata.
      */
    private[kyo] def currentColumnToken(): Int

    /** Resolves the row field the column at `index` belongs to, through the matcher the codec built; positional when no matcher or no
      * field matches. See the block comment on [[SqlFieldMatcher]] for why a blanket positional accept would corrupt a by-name decode.
      */
    override private[kyo] def fieldIndex(index: Int, names: Chunk[String]): Int =
        var j = 0
        while j < names.size do
            if matches(index, names(j)) then return j
            j += 1
        end while
        index
    end fieldIndex

    /** The field-matching rule for this row, resolved once. */
    private lazy val matches: (Int, String) => Boolean =
        matchesFieldAt.getOrElse(SqlFieldMatcher.verbatim(row.columnNames))

end SqlPositionalRowReader

object SqlPositionalRowReader:

    /** Decodes a single column value from `bytes`, the shared body of each backend's `readValue`.
      *
      * The entry point for reading a value that arrived nested inside another value's payload, a range end being the case that needs it. A
      * decode failure is thrown, as everywhere in the reader contract. The backend supplies its own unspecified type token, its own row
      * codec, and the reader to run.
      */
    private[kyo] def readSingleValue[A](
        column: SqlSchema.Column[A],
        bytes: Span[Byte],
        unspecifiedToken: Int,
        codec: SqlRow.Codec,
        makeReader: SqlRow => SqlCodec.Reader
    )(using Frame): A =
        val row = new SqlRow(Chunk(Maybe(bytes)), Chunk(SqlRow.Column("", unspecifiedToken)), codec)
        column.read(makeReader(row))
    end readSingleValue

end SqlPositionalRowReader
