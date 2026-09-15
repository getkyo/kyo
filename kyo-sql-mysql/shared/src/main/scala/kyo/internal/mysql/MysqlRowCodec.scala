package kyo.internal.mysql

import kyo.<
import kyo.Abort
import kyo.Frame
import kyo.Maybe
import kyo.Span
import kyo.SqlCodec
import kyo.SqlCodec.Format
import kyo.SqlDecodeColumnAbsentException
import kyo.SqlDecodeException
import kyo.SqlRow
import kyo.SqlSchema
import kyo.SqlValue
import kyo.internal.SqlPositionalRowCodec
import kyo.internal.SqlValueRender
import kyo.internal.mysql.types.MysqlEncoder

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

    override def columnKind(typeToken: Int): SqlRow.ColumnKind =
        if !MysqlColumnToken.isSpecified(typeToken) then SqlRow.ColumnKind.Unknown
        // A binary-collated string-family column carries bytes, not text, and shares its type byte with the text
        // twin `types` names, so the collation decides before the table is consulted.
        else if MysqlColumnToken.isBinaryString(typeToken) then SqlRow.ColumnKind.Bytes
        else MysqlRowCodec.kindOf(MysqlColumnToken.columnType(typeToken))

    override def typeName(typeToken: Int): Maybe[String] =
        if !MysqlColumnToken.isSpecified(typeToken) then Maybe.empty
        else if MysqlColumnToken.isBinaryString(typeToken) then
            MysqlRowCodec.binaryTypeName(MysqlColumnToken.columnType(typeToken))
        // An ENUM and a SET both arrive as STRING, which the table names CHAR; the flags word is the only thing that
        // says which, so a caller asking what the server reported gets the declared type rather than its carrier.
        else if MysqlColumnToken.isEnum(typeToken) then Maybe("ENUM")
        else if MysqlColumnToken.isSet(typeToken) then Maybe("SET")
        else MysqlRowCodec.nameOf(MysqlColumnToken.columnType(typeToken))

    /** A `FLOAT` column is the narrower of the two widths this backend maps to one kind. */
    override private[kyo] def isSingleWidthFloat(typeToken: Int): Boolean =
        MysqlColumnToken.columnType(typeToken) == MysqlEncoder.TYPE_FLOAT

    /** Reads the kinds whose wire only this backend can interpret, under BOTH wire formats.
      *
      * `TIME` is a signed span reaching -838:59:59, so the `LocalTime` the shared path reads refuses half the column's range; the `Duration`
      * that spans it makes both protocols answer alike. `DATE` and `DATETIME` are here because the server pads a `DATETIME(N)` fraction to
      * the column's declared scale, and rendering from the value trims it instead.
      *
      * `TIMESTAMP` is not here: the shared path reads it as an instant and renders it at UTC, which is right for both backends.
      */
    override def columnValue(row: SqlRow, idx: Int)(using Frame): SqlValue < Abort[SqlDecodeException] =
        import SqlRow.ColumnKind
        val column = row.columns(idx)
        def bytesOf: Span[Byte] < Abort[SqlDecodeException] =
            row.column(idx) match
                case Maybe.Present(bytes) => bytes
                case Maybe.Absent         => Abort.fail(SqlDecodeColumnAbsentException(idx))
        def decoded(f: Span[Byte] => SqlValue): SqlValue < Abort[SqlDecodeException] =
            bytesOf.map(bytes => SqlRow.Codec.catchingColumn(Maybe(idx))(f(bytes)))
        columnKind(column.typeToken) match
            case ColumnKind.Time =>
                decoded { bytes =>
                    val span = MysqlRowReader.decodeDuration(bytes, format)
                    val abs  = span.abs
                    SqlValue.Time(
                        span.isNegative,
                        abs.toHours,
                        abs.toMinutesPart,
                        abs.toSecondsPart,
                        abs.toNanosPart / 1000
                    )
                }
            case ColumnKind.Date =>
                decoded { bytes =>
                    val date = MysqlRowReader.decodeDate(bytes, format)
                    SqlValue.Date(date.getYear, date.getMonthValue, date.getDayOfMonth, bc = false)
                }
            case ColumnKind.DateTime =>
                decoded { bytes =>
                    val value = MysqlRowReader.decodeDatetime(bytes, format)
                    SqlValue.DateTime(
                        value.getYear,
                        value.getMonthValue,
                        value.getDayOfMonth,
                        bc = false,
                        value.getHour,
                        value.getMinute,
                        value.getSecond,
                        value.getNano / 1000
                    )
                }
            case _ => super.columnValue(row, idx)
        end match
    end columnValue

end MysqlRowCodec

private[kyo] object MysqlRowCodec:

    import kyo.internal.mysql.types.MysqlEncoder.*

    /** Every type byte this backend can name, with the MySQL spelling and the neutral kind it maps to.
      *
      * One table rather than two so the two answers a caller gets about a column, [[kyo.SqlRow.columnTypeName]] and
      * [[kyo.SqlRow.columnKind]], cannot drift apart. A type byte absent from it answers `Absent` and [[kyo.SqlRow.ColumnKind.Unknown]].
      *
      * A `TEXT` column and a `BLOB` column share the BLOB type bytes and are told apart only by the column's character set, which the token
      * carries as the binary-collation bit [[MysqlColumnToken]] packs from charset 63. The entries below name the text half; a
      * binary-collated column of the same type byte is answered by [[binaryNames]] and [[kyo.SqlRow.ColumnKind.Bytes]] instead.
      */
    private val types: Map[Int, (String, SqlRow.ColumnKind)] = Map(
        TYPE_TINY        -> ("TINYINT", SqlRow.ColumnKind.Integer),
        TYPE_SHORT       -> ("SMALLINT", SqlRow.ColumnKind.Integer),
        TYPE_INT24       -> ("MEDIUMINT", SqlRow.ColumnKind.Integer),
        TYPE_LONG        -> ("INT", SqlRow.ColumnKind.Integer),
        TYPE_LONGLONG    -> ("BIGINT", SqlRow.ColumnKind.Integer),
        TYPE_YEAR        -> ("YEAR", SqlRow.ColumnKind.Integer),
        TYPE_BIT         -> ("BIT", SqlRow.ColumnKind.Integer),
        TYPE_FLOAT       -> ("FLOAT", SqlRow.ColumnKind.Float),
        TYPE_DOUBLE      -> ("DOUBLE", SqlRow.ColumnKind.Float),
        TYPE_DECIMAL     -> ("DECIMAL", SqlRow.ColumnKind.Decimal),
        TYPE_NEWDECIMAL  -> ("DECIMAL", SqlRow.ColumnKind.Decimal),
        TYPE_DATE        -> ("DATE", SqlRow.ColumnKind.Date),
        TYPE_TIME        -> ("TIME", SqlRow.ColumnKind.Time),
        TYPE_DATETIME    -> ("DATETIME", SqlRow.ColumnKind.DateTime),
        TYPE_TIMESTAMP   -> ("TIMESTAMP", SqlRow.ColumnKind.Timestamp),
        TYPE_JSON        -> ("JSON", SqlRow.ColumnKind.Json),
        TYPE_VARCHAR     -> ("VARCHAR", SqlRow.ColumnKind.Text),
        TYPE_VAR_STRING  -> ("VARCHAR", SqlRow.ColumnKind.Text),
        TYPE_STRING      -> ("CHAR", SqlRow.ColumnKind.Text),
        TYPE_ENUM        -> ("ENUM", SqlRow.ColumnKind.Text),
        TYPE_SET         -> ("SET", SqlRow.ColumnKind.Text),
        TYPE_TINY_BLOB   -> ("TINYTEXT", SqlRow.ColumnKind.Text),
        TYPE_MEDIUM_BLOB -> ("MEDIUMTEXT", SqlRow.ColumnKind.Text),
        TYPE_LONG_BLOB   -> ("LONGTEXT", SqlRow.ColumnKind.Text),
        TYPE_BLOB        -> ("TEXT", SqlRow.ColumnKind.Text),
        // WKB behind a four-byte SRID, so a String read of one answers the geometry's bytes.
        TYPE_GEOMETRY -> ("GEOMETRY", SqlRow.ColumnKind.Bytes)
    )

    /** The kind and name lookups as flat arrays indexed by the type byte, derived from [[types]] so a type added there reaches both.
      *
      * Arrays rather than the map they come from because these are read once per column per row, and a `Map[Int, V]` boxes its key on every
      * lookup. A type byte is one byte, so 256 entries cover every token.
      */
    private val TypeByteCount = 256

    private val kindByType: Array[SqlRow.ColumnKind] =
        val arr = Array.fill[SqlRow.ColumnKind](TypeByteCount)(SqlRow.ColumnKind.Unknown)
        types.foreach((byte, entry) => arr(byte) = entry._2)
        arr
    end kindByType

    private val nameByType: Array[Maybe[String]] =
        val arr = Array.fill[Maybe[String]](TypeByteCount)(Maybe.empty)
        types.foreach((byte, entry) => arr(byte) = Maybe(entry._1))
        arr
    end nameByType

    private[mysql] def kindOf(columnType: Int): SqlRow.ColumnKind =
        if columnType < 0 || columnType >= TypeByteCount then SqlRow.ColumnKind.Unknown
        else kindByType(columnType)

    private[mysql] def nameOf(columnType: Int): Maybe[String] =
        if columnType < 0 || columnType >= TypeByteCount then Maybe.empty
        else nameByType(columnType)

    /** The MySQL name of the type `columnType` names when its KIND conflicts with `accepted`, and [[Maybe.empty]] when the read may proceed.
      *
      * The general form of [[nonTextColumnType]]. A read names the kinds its target can be decoded from, and any other named kind is refused:
      * without the check a `LocalDate` field over an `INT` column reaches the temporal decoder, which reports the BYTES rather than the column
      * mismatch. An unnamed type byte is accepted: a token with no known meaning is not evidence of a mismatch.
      */
    private[mysql] def conflictingColumnType(
        columnType: Int,
        isBinaryString: Boolean,
        accepted: Set[SqlRow.ColumnKind]
    ): Maybe[String] =
        if isBinaryString then
            if accepted.contains(SqlRow.ColumnKind.Bytes) then Maybe.empty
            else Maybe(binaryNames.getOrElse(columnType, "BINARY"))
        else
            types.get(columnType) match
                case Some((name, kind)) if !accepted.contains(kind) => Maybe(name)
                case _                                              => Maybe.empty

    /** The MySQL name of the type `columnType` names when a text read of it would reinterpret its bytes rather than render its value.
      *
      * Derived from the one type table above rather than from a second list, so what this refuses and what [[kyo.SqlRow.columnKind]] and
      * [[kyo.SqlRow.columnTypeName]] report cannot drift apart. A type whose kind is [[kyo.SqlRow.ColumnKind.Text]] or
      * [[kyo.SqlRow.ColumnKind.Json]] carries its text rendering on the wire under both protocols and is not refused; every other named kind
      * is. An unnamed type byte is not refused either: a token with no known meaning is not evidence of a mismatch.
      *
      * `DECIMAL` is refused even though its bytes are ASCII digits: the schema decides, and a row type declaring `String` for a number is
      * wrong about the column. That argument does not extend to `JSON`, whose wire bytes ARE its rendering, so it stays readable here while
      * the other engine refuses `jsonb`.
      */
    private[mysql] def nonTextColumnType(columnType: Int, isBinaryString: Boolean): Maybe[String] =
        if isBinaryString then Maybe(binaryNames.getOrElse(columnType, "BINARY"))
        else
            types.get(columnType) match
                case Some((name, kind)) if !isTextReadableKind(kind) => Maybe(name)
                case _                                               => Maybe.empty

    /** Whether the type `columnType` names carries its value's text rendering on the wire, so a `String` read of it converts rather than
      * reinterprets. False for a type byte the table does not name, which knows nothing either way.
      *
      * Answers for the type byte alone. A string-family byte is text only when its collation is not `binary`, which the caller settles
      * through [[MysqlColumnToken.isBinaryString]] before asking.
      */
    private[mysql] def isTextReadableType(columnType: Int): Boolean =
        types.get(columnType).exists((_, kind) => isTextReadableKind(kind))

    /** Whether a column of this kind carries its value's text rendering on the wire. */
    private def isTextReadableKind(kind: SqlRow.ColumnKind): Boolean =
        kind == SqlRow.ColumnKind.Text || kind == SqlRow.ColumnKind.Json

    /** The type bytes MySQL uses for both a text column and its binary twin, which only the collation separates.
      *
      * `JSON` is deliberately not here: it has its own type byte and its bytes are the document text, even though the server reports it
      * as `binary`-collated.
      */
    private val stringFamily: Set[Int] = Set(
        TYPE_VARCHAR,
        TYPE_VAR_STRING,
        TYPE_STRING,
        TYPE_TINY_BLOB,
        TYPE_MEDIUM_BLOB,
        TYPE_LONG_BLOB,
        TYPE_BLOB
    )

    /** Whether `columnType` is a type byte MySQL shares between a text column and its binary twin. */
    private[mysql] def isStringFamily(columnType: Int): Boolean = stringFamily.contains(columnType)

    /** How MySQL spells the binary twin of each string-family type byte, for the column types `types` names on their text side. */
    private val binaryNames: Map[Int, String] = Map(
        TYPE_VARCHAR     -> "VARBINARY",
        TYPE_VAR_STRING  -> "VARBINARY",
        TYPE_STRING      -> "BINARY",
        TYPE_TINY_BLOB   -> "TINYBLOB",
        TYPE_MEDIUM_BLOB -> "MEDIUMBLOB",
        TYPE_LONG_BLOB   -> "LONGBLOB",
        TYPE_BLOB        -> "BLOB"
    )

    /** The MySQL spelling of a binary-collated string-family column, for [[kyo.SqlRow.columnTypeName]]. */
    private[mysql] def binaryTypeName(columnType: Int): Maybe[String] =
        Maybe.fromOption(binaryNames.get(columnType))

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
            source.columns.map(column => SqlRow.Column(column.name, MysqlColumnToken(column.columnType, column.flags, column.charset))),
            MysqlRowCodec(source.format)
        )

end MysqlRowCodec
