package kyo.internal.mysql

/** The single backend token [[kyo.SqlRow.Column]] carries for a MySQL result column.
  *
  * `SqlRow.Column` holds one opaque `Int` per column whose meaning belongs to the backend that produced the row (a type OID on the
  * PostgreSQL side). MySQL's decode path needs two of the server's per-column fields, and both fit in that one `Int`:
  *
  *   - [[ColumnDefinition41.columnType]], the MySQL type byte, says which wire representation the value has. Width alone cannot say: in the
  *     binary protocol `LONG` and `FLOAT` are both four bytes and `LONGLONG` and `DOUBLE` are both eight, and `NEWDECIMAL` arrives as ASCII
  *     text even there.
  *   - the UNSIGNED bit of [[ColumnDefinition41.flags]] says whether the value's top bit is a sign or a magnitude. Without it a
  *     `BIGINT UNSIGNED` above 2^63 and an `INT UNSIGNED` above 2^31 both read as negative numbers.
  *
  * Layout: the type byte in bits 0 to 7, the flags word in bits 8 to 23, and [[Specified]] in bit 24. That last bit is what separates a real
  * `TYPE_DECIMAL` column (type byte 0, no flags) from [[Unspecified]], which a row assembled without server metadata carries. Encoding the
  * absence as a distinguishable bit rather than as the value zero is the difference between a token that can say "I do not know" and one that
  * lies about a `DECIMAL` column.
  */
private[kyo] object MysqlColumnToken:

    import kyo.Maybe
    import kyo.internal.mysql.types.MysqlEncoder

    /** A column whose server metadata is not available. Every read then falls back to the value's byte width. */
    val Unspecified: Int = 0

    private val Specified = 1 << 24

    /** Set when the column's collation is `binary` (id 63), which is the only thing that separates a `BLOB` from a `TEXT`, a `VARBINARY`
      * from a `VARCHAR`, and a `BINARY` from a `CHAR`: MySQL gives each pair the same type byte.
      *
      * One derived bit rather than the collation id, which does not fit: the id is 16 bits and only 7 are free. The id's other values
      * carry no decode meaning here, since every non-binary collation makes the column text and the codec reads it as UTF-8 either way.
      *
      * NOT the `BINARY_FLAG` already in the flags word: MySQL sets that for a `*_bin`-collated TEXT column too, so it would call
      * `CHAR ... COLLATE utf8mb4_bin` bytes.
      */
    private val BinaryCollation = 1 << 25

    /** MySQL's `binary` collation id, `my_charset_bin` in the server. */
    private val BinaryCollationId = 63

    /** Bit 0x20 of `ColumnDefinition41.flags`, `UNSIGNED_FLAG` in MySQL's `mysql_com.h`. */
    private val UnsignedFlag = 0x20

    /** Packs a result column's type byte, flags word, and whether its collation is binary into the token the neutral row carries. */
    def apply(columnType: Int, flags: Int, charset: Int): Int =
        val binary = if charset == BinaryCollationId then BinaryCollation else 0
        Specified | binary | (columnType & 0xff) | ((flags & 0xffff) << 8)
    end apply

    /** Whether `token` carries server metadata at all. */
    def isSpecified(token: Int): Boolean = (token & Specified) != 0

    /** The MySQL type byte `token` carries. Meaningful only when [[isSpecified]]. */
    def columnType(token: Int): Int = token & 0xff

    /** Whether the column's integer values are unsigned. False for an [[Unspecified]] token, which knows nothing either way. */
    def isUnsigned(token: Int): Boolean = (token & (UnsignedFlag << 8)) != 0

    /** The MySQL name of `token`'s column type when it names a type whose bytes a text read would reinterpret rather than render. Absent
      * for a text column, for a type byte with no known meaning, and for an [[Unspecified]] token.
      *
      * Answered by [[MysqlRowCodec.nonTextColumnType]], which derives it from the one type table that also backs
      * [[kyo.SqlRow.columnKind]] and [[kyo.SqlRow.columnTypeName]], so the three answers cannot drift apart.
      */
    def nonTextColumnType(token: Int): Maybe[String] =
        if !isSpecified(token) then Maybe.empty
        else MysqlRowCodec.nonTextColumnType(columnType(token), isBinaryString(token))

    /** Whether `token` names a column whose value arrives as its text rendering. False for an [[Unspecified]] token, which knows nothing
      * either way, and for a type byte the table does not name.
      */
    def isTextColumn(token: Int): Boolean =
        isSpecified(token) && !isBinaryString(token) && MysqlRowCodec.isTextReadableType(columnType(token))

    /** Whether `token` names an `ENUM` column. MySQL reports one as `STRING` and puts `ENUM_FLAG` in the flags word, so the type byte
      * alone calls it a `CHAR`.
      */
    def isEnum(token: Int): Boolean =
        isSpecified(token) && (token & (MysqlEncoder.ENUM_FLAG << 8)) != 0

    /** Whether `token` names a `SET` column, reported the way [[isEnum]] describes. */
    def isSet(token: Int): Boolean =
        isSpecified(token) && (token & (MysqlEncoder.SET_FLAG << 8)) != 0

    /** Whether `token` names a column carrying arbitrary bytes rather than text: one of the string-family type bytes, collated `binary`.
      *
      * The two conditions are both required. The type byte alone cannot say, since `BLOB` and `TEXT` share one. The collation alone
      * cannot either, since MySQL reports a `JSON` column as `binary` too, and a JSON column's bytes genuinely are the document text.
      * `JSON` has its own type byte (0xf5) outside the string family, which is what keeps it out of this.
      */
    def isBinaryString(token: Int): Boolean =
        isSpecified(token) && (token & BinaryCollation) != 0 && MysqlRowCodec.isStringFamily(columnType(token))

end MysqlColumnToken
