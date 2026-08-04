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

    /** A column whose server metadata is not available. Every read then falls back to the value's byte width. */
    val Unspecified: Int = 0

    private val Specified = 1 << 24

    /** Bit 0x20 of `ColumnDefinition41.flags`, `UNSIGNED_FLAG` in MySQL's `mysql_com.h`. */
    private val UnsignedFlag = 0x20

    /** Packs a result column's type byte and flags word into the token the neutral row carries. */
    def apply(columnType: Int, flags: Int): Int =
        Specified | (columnType & 0xff) | ((flags & 0xffff) << 8)

    /** Whether `token` carries server metadata at all. */
    def isSpecified(token: Int): Boolean = (token & Specified) != 0

    /** The MySQL type byte `token` carries. Meaningful only when [[isSpecified]]. */
    def columnType(token: Int): Int = token & 0xff

    /** Whether the column's integer values are unsigned. False for an [[Unspecified]] token, which knows nothing either way. */
    def isUnsigned(token: Int): Boolean = (token & (UnsignedFlag << 8)) != 0

end MysqlColumnToken
