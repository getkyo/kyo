package kyo.internal.mysql

import kyo.*
import kyo.SqlCodec.Format
import kyo.internal.mysql.types.MysqlEncoder

/** Unit tests for [[MysqlRowCodec.row]], the bridge from a MySQL wire row to the backend-neutral [[kyo.SqlRow]].
  *
  * The bridge carries three things the decode path cannot work without: the wire format, the server's column type byte, and its UNSIGNED
  * flag. Binary and text rows encode the same value differently, so a row whose format is dropped on the way over decodes its bytes under
  * the wrong rules and produces garbage rather than an error. The leaves are in two halves: the first pins that each of the three crosses
  * the bridge, and the second decodes real rows through `SqlRow.decode` under what crossed, because a tag that arrives and is then ignored
  * is the same defect as a tag that never arrives.
  */
class MysqlRowCodecTest extends kyo.Test:

    private val column = ColumnDefinition41(
        catalog = "",
        schema = "",
        table = "",
        orgTable = "",
        name = "n",
        orgName = "n",
        charset = 0,
        columnLength = 0L,
        columnType = 0,
        flags = 0,
        decimals = 0
    )

    private val values = Chunk(Maybe(Span[Byte](0.toByte)))

    "an extended-protocol row keeps its Binary format across the bridge" in {
        // The transaction, cancel, and pipeline paths each bridge an extended-protocol MysqlRow; the bridge must
        // forward its Binary format, or MysqlRowReader would decode binary bytes as text.
        val binaryRow = new MysqlRow(values, Chunk(column), Format.Binary)
        assert(
            MysqlRowCodec.row(binaryRow).codec == MysqlRowCodec(Format.Binary),
            "the bridge must forward the Binary format from an extended-protocol MysqlRow"
        )
    }

    "a simple-query row keeps its Text format across the bridge" in {
        val textRow = new MysqlRow(values, Chunk(column), Format.Text)
        assert(
            MysqlRowCodec.row(textRow).codec == MysqlRowCodec(Format.Text),
            "the bridge must forward the Text format from a simple-query MysqlRow"
        )
    }

    "column names carry across the bridge" in {
        val binaryRow = new MysqlRow(values, Chunk(column), Format.Binary)
        assert(
            MysqlRowCodec.row(binaryRow).columnNames == Chunk("n"),
            "the bridge must carry the MySQL column names over"
        )
    }

    "the column type byte and the UNSIGNED flag carry across the bridge" in {
        val unsignedBigint = column.copy(columnType = MysqlEncoder.TYPE_LONGLONG, flags = 0x20)
        val bridged        = MysqlRowCodec.row(new MysqlRow(values, Chunk(unsignedBigint), Format.Binary))
        val token          = bridged.columns(0).typeToken
        assert(MysqlColumnToken.isSpecified(token), "a bridged column must report that it carries server metadata")
        assert(
            MysqlColumnToken.columnType(token) == MysqlEncoder.TYPE_LONGLONG,
            s"expected the LONGLONG type byte, got ${MysqlColumnToken.columnType(token)}"
        )
        assert(MysqlColumnToken.isUnsigned(token), "the UNSIGNED flag decides whether the top bit is a sign or a magnitude")
    }

    // == Decoding an actual row under the format the bridge carried ===============================
    //
    // The three leaves above pin that the format TAG crosses the bridge, which on its own leaves the hazard unexercised:
    // nothing there decodes a value under the tag. A text row's bytes are the value's ASCII rendering, so the digits of
    // `1234` parsed as a little-endian LONG are 875770417 and the ASCII `0` of a false boolean is the nonzero byte 0x30.
    // These leaves decode real rows through `SqlRow.decode`, the schema path a caller uses, so a reader that ignores the
    // tag fails them.

    private def textRowOf(columnType: Int, rendering: String): SqlRow =
        MysqlRowCodec.row(
            new MysqlRow(
                Chunk(Maybe(Span.from(rendering.getBytes(java.nio.charset.StandardCharsets.UTF_8)))),
                Chunk(column.copy(columnType = columnType)),
                Format.Text
            )
        )

    /** Decodes `row` through the schema path, failing the leaf on an abort so the assertion below is about the VALUE. */
    private def decode[A](row: SqlRow)(using kyo.SqlSchema[A], Frame, kyo.test.AssertScope): A =
        Abort.run[SqlDecodeException](row.decode[A]).eval match
            case Result.Success(a) => a
            case other             => fail(s"expected a decoded value, got $other")

    "a text row's four-digit integer decodes as its value through the schema path" in {
        assert(
            decode[Int](textRowOf(MysqlEncoder.TYPE_LONG, "1234")) == 1234,
            "the ASCII digits of 1234 must decode as 1234, not as the 875770417 their little-endian reading spells"
        )
    }

    "a text row's one-digit integer decodes as its value through the schema path" in {
        assert(
            decode[Int](textRowOf(MysqlEncoder.TYPE_LONG, "7")) == 7,
            "one ASCII digit is a value, not a LONG three bytes short"
        )
    }

    "a text row's three-digit integer decodes as its value through the schema path" in {
        assert(decode[Int](textRowOf(MysqlEncoder.TYPE_LONG, "999")) == 999)
    }

    "a text row's false boolean decodes as false through the schema path" in {
        assert(
            !decode[Boolean](textRowOf(MysqlEncoder.TYPE_TINY, "0")),
            "the ASCII byte of 0 is 0x30, so reading the byte rather than the value reported every false as true"
        )
    }

    "a text row's true boolean decodes as true through the schema path" in {
        assert(decode[Boolean](textRowOf(MysqlEncoder.TYPE_TINY, "1")))
    }

    "a text row's BIGINT decodes at full width through the schema path" in {
        assert(decode[Long](textRowOf(MysqlEncoder.TYPE_LONGLONG, "9007199254740993")) == 9007199254740993L)
    }

    "a binary row still decodes under its own format after the bridge" in {
        // The other half of the tag: the same reader, the same bridge, the little-endian rules.
        val binary = MysqlRowCodec.row(
            new MysqlRow(
                Chunk(Maybe(Span[Byte](0xd2.toByte, 0x04.toByte, 0x00.toByte, 0x00.toByte))),
                Chunk(column.copy(columnType = MysqlEncoder.TYPE_LONG)),
                Format.Binary
            )
        )
        assert(decode[Int](binary) == 1234, "0x000004d2 little-endian is 1234")
    }

end MysqlRowCodecTest
