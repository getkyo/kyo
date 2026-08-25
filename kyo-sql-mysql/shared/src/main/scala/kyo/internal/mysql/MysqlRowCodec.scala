package kyo.internal.mysql

import kyo.Frame
import kyo.Maybe
import kyo.SqlCodec
import kyo.SqlCodec.Format
import kyo.SqlRow
import kyo.SqlSchema
import kyo.internal.SqlPositionalRowCodec

/** The MySQL backend's [[SqlRow.Codec]]: decodes a row's columns through [[MysqlRowReader]].
  *
  * Carries the wire format of the result set that produced the row: [[Format.Binary]] for prepared-statement results, [[Format.Text]] for
  * text-protocol ones. Being a case class, two codecs for the same format are equal, so [[SqlRow]] equality stays format-sensitive.
  *
  * @param format
  *   the wire format of every column in the rows this codec decodes
  */
final private[kyo] case class MysqlRowCodec(format: Format) extends SqlPositionalRowCodec:

    def newReader(sliced: SqlRow, matchesFieldAt: Maybe[(Int, String) => Boolean])(using Frame): SqlCodec.Reader =
        new MysqlRowReader(sliced, format, matchesFieldAt)

end MysqlRowCodec

private[kyo] object MysqlRowCodec:

    /** Builds a [[SqlRow]] from a [[MysqlRow]].
      *
      * MySQL reports no PostgreSQL-style OIDs, so the neutral column's type token carries a [[MysqlColumnToken]] instead: the server's type
      * byte and flags word packed into the one `Int` the neutral row has room for. The decode path needs both, the type byte to tell a
      * four-byte `LONG` from a four-byte `FLOAT`, and the UNSIGNED flag to tell a magnitude's top bit from a sign. The codec identity, not the
      * token, is still what tells the decode path which backend produced the row.
      */
    private[kyo] def row(source: MysqlRow): SqlRow =
        new SqlRow(
            source.values,
            source.columns.map(column => SqlRow.Column(column.name, MysqlColumnToken(column.columnType, column.flags))),
            MysqlRowCodec(source.format)
        )

end MysqlRowCodec
