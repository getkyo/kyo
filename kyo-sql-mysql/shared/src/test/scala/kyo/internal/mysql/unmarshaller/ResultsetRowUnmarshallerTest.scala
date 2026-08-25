package kyo.internal.mysql.unmarshaller

import kyo.*
import kyo.Test
import kyo.internal.mysql.MysqlBufferReader

/** Unit tests for [[ResultsetRowUnmarshaller]], the MySQL text-protocol result-set row decoder.
  *
  * A text-protocol row is a run of per-column length prefixes, and two first bytes are reserved: `0xFB` marks SQL NULL and `0xFF` marks an
  * ERR packet, which inside a row means the stream has desynchronised. Handing a reserved byte on to the length read as `-1` would tell the
  * caller the packet had run out of data and send them looking at buffer sizes. These leaves pin each byte to the diagnosis it deserves,
  * and drive the reader over a literal byte array, so no server is involved.
  */
class ResultsetRowUnmarshallerTest extends Test:

    "a 0xFF length prefix names the byte and its offset, not a byte shortage" in {
        val reader = MysqlBufferReader(Array[Byte](0xff.toByte))
        Abort.run[SqlDecodeException](ResultsetRowUnmarshaller(1).read(reader)).map {
            case Result.Failure(e: SqlDecodeProtocolFormatException) =>
                assert(e.messageByte == 0xff.toByte)
                assert(e.position == 0)
            case other => fail(s"Expected a protocol-format failure naming 0xFF at offset 0, got: $other")
        }
    }

    // The reader this unmarshaller delegates to must keep 0xFB meaning SQL NULL. Routing the row through a lenenc
    // read that mapped 0xFB to a failure would turn every NULL column in every text-protocol result set into an
    // error.
    "a 0xFB column is SQL NULL, and a following column still decodes" in {
        val bytes  = Array[Byte](0xfb.toByte, 3, 'a'.toByte, 'b'.toByte, 'c'.toByte)
        val reader = MysqlBufferReader(bytes)
        Abort.run[SqlDecodeException](ResultsetRowUnmarshaller(2).read(reader)).map {
            case Result.Success(row) =>
                assert(row.values.size == 2)
                assert(row.values(0) == Maybe.Absent)
                row.values(1) match
                    case Maybe.Present(b) => assert(b.toArray.toSeq == Seq[Byte]('a', 'b', 'c'))
                    case Maybe.Absent     => fail("Expected column 1 to carry bytes, got NULL")
            case other => fail(s"Expected a two-column row (NULL, 'abc'), got: $other")
        }
    }

    "a 0xFF prefix on a later column reports that column's own offset" in {
        val bytes  = Array[Byte](2, 'h'.toByte, 'i'.toByte, 0xff.toByte)
        val reader = MysqlBufferReader(bytes)
        Abort.run[SqlDecodeException](ResultsetRowUnmarshaller(2).read(reader)).map {
            case Result.Failure(e: SqlDecodeProtocolFormatException) =>
                assert(e.messageByte == 0xff.toByte)
                assert(e.position == 3)
            case other => fail(s"Expected a protocol-format failure naming 0xFF at offset 3, got: $other")
        }
    }

end ResultsetRowUnmarshallerTest
