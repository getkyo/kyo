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

    override def columnKind(typeToken: Int): SqlRow.ColumnKind =
        if !MysqlColumnToken.isSpecified(typeToken) then SqlRow.ColumnKind.Unknown
        // A binary-collated string-family column carries bytes, not text, and shares its type byte with the text
        // twin `types` names, so the collation decides before the table is consulted.
        else if MysqlColumnToken.isBinaryString(typeToken) then SqlRow.ColumnKind.Bytes
        else MysqlRowCodec.kinds.getOrElse(MysqlColumnToken.columnType(typeToken), SqlRow.ColumnKind.Unknown)

    override def typeName(typeToken: Int): Maybe[String] =
        if !MysqlColumnToken.isSpecified(typeToken) then Maybe.empty
        else if MysqlColumnToken.isBinaryString(typeToken) then
            MysqlRowCodec.binaryTypeName(MysqlColumnToken.columnType(typeToken))
        // An ENUM and a SET both arrive as STRING, which the table names CHAR; the flags word is the only thing that
        // says which, so a caller asking what the server reported gets the declared type rather than its carrier.
        else if MysqlColumnToken.isEnum(typeToken) then Maybe("ENUM")
        else if MysqlColumnToken.isSet(typeToken) then Maybe("SET")
        else Maybe.fromOption(MysqlRowCodec.typeNames.get(MysqlColumnToken.columnType(typeToken)))

end MysqlRowCodec

private[kyo] object MysqlRowCodec:

    import kyo.internal.mysql.types.MysqlEncoder.*

    /** Every type byte this backend can name, with the MySQL spelling and the neutral kind it maps to.
      *
      * One table rather than two so the two answers a caller gets about a column, [[kyo.SqlRow.columnTypeName]] and
      * [[kyo.SqlRow.columnKind]], cannot drift apart. A type byte absent from it answers `Absent` and [[kyo.SqlRow.ColumnKind.Unknown]].
      *
      * A `TEXT` column and a `BLOB` column share the BLOB type bytes and are told apart only by the column's character set, which the
      * neutral token has no room for, so those bytes are named for the wider of the two and mapped to
      * [[kyo.SqlRow.ColumnKind.Text]]: MySQL's text types are the common case, and the values arrive as their text rendering either way.
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

    private val typeNames: Map[Int, String]        = types.view.mapValues(_._1).toMap
    private val kinds: Map[Int, SqlRow.ColumnKind] = types.view.mapValues(_._2).toMap

    /** The MySQL name of the type `columnType` names when a text read of it would reinterpret its bytes rather than render its value.
      *
      * Derived from the one type table above rather than from a second list, so what this refuses and what [[kyo.SqlRow.columnKind]] and
      * [[kyo.SqlRow.columnTypeName]] report cannot drift apart. A type whose kind is [[kyo.SqlRow.ColumnKind.Text]] or
      * [[kyo.SqlRow.ColumnKind.Json]] carries its text rendering on the wire under both protocols and is not refused; every other named
      * kind is. A type byte the table does not name is not refused either, for the reason [[MysqlNumericDecoder]] does not refuse an
      * unrecognised one: a token with no known meaning is not evidence of a mismatch.
      *
      * `DECIMAL` is refused even though its bytes are ASCII digits under both protocols, so nothing would corrupt. What decides it is the
      * schema: a `DECIMAL` column is a number, and a row type declaring `String` for one is wrong about the column on both engines.
      * Admitting it here and refusing it on PostgreSQL, where `numeric` binary is a struct, would make the same row type decode against
      * one engine and fail against the other.
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
