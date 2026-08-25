package kyo.internal.mysql

import kyo.*
import kyo.Span
import kyo.SqlException
import kyo.Test

/** Tests for [[MysqlBufferReader]].
  *
  * Verifies little-endian decoding for all integer widths, lenenc integer round-trips across all four size boundaries, lenenc strings,
  * NUL-terminated strings, and fixed-length strings.
  *
  * The lenenc leaves also pin what the two reserved first bytes do, and they are the point of the pair: `0xFB` is SQL NULL to
  * `readLenencBytes` and a protocol fault to `readLenencInt`, while `0xFF` is a protocol fault to both. Each failing leaf asserts the byte
  * and the offset the diagnosis carries, because a reader that reported only "malformed" could name `0xFF` whichever byte was actually
  * there and still satisfy the assertion.
  */
class MysqlBufferReaderTest extends Test:

    "MysqlBufferReader readUInt16LE [1,0] = 1" in {
        val reader = MysqlBufferReader(Array[Byte](1, 0))
        Abort.run[SqlDecodeException](reader.readUInt16LE()).map {
            case Result.Success(v) => assert(v == 1)
            case other             => fail(s"Expected Success(1), got: $other")
        }
    }

    "MysqlBufferReader readUInt16LE [0,1] = 256" in {
        val reader = MysqlBufferReader(Array[Byte](0, 1))
        Abort.run[SqlDecodeException](reader.readUInt16LE()).map {
            case Result.Success(v) => assert(v == 256)
            case other             => fail(s"Expected Success(256), got: $other")
        }
    }

    "MysqlBufferReader readUInt32LE [1,0,0,0] = 1" in {
        val reader = MysqlBufferReader(Array[Byte](1, 0, 0, 0))
        Abort.run[SqlDecodeException](reader.readUInt32LE()).map {
            case Result.Success(v) => assert(v == 1L)
            case other             => fail(s"Expected Success(1L), got: $other")
        }
    }

    "MysqlBufferReader readUInt32LE [255,255,255,255] = 4294967295" in {
        val reader = MysqlBufferReader(Array[Byte](0xff.toByte, 0xff.toByte, 0xff.toByte, 0xff.toByte))
        Abort.run[SqlDecodeException](reader.readUInt32LE()).map {
            case Result.Success(v) => assert(v == 4294967295L)
            case other             => fail(s"Expected Success(4294967295L), got: $other")
        }
    }

    "MysqlBufferReader readLengthEncodedInt 1-byte value 42" in {
        val reader = MysqlBufferReader(Array[Byte](42))
        Abort.run[SqlDecodeException](reader.readLenencInt()).map {
            case Result.Success(v) => assert(v == 42L)
            case other             => fail(s"Expected Success(42L), got: $other")
        }
    }

    "MysqlBufferReader readLengthEncodedInt 0xFC prefix encodes 100" in {
        val reader = MysqlBufferReader(Array[Byte](0xfc.toByte, 100, 0))
        Abort.run[SqlDecodeException](reader.readLenencInt()).map {
            case Result.Success(v) => assert(v == 100L)
            case other             => fail(s"Expected Success(100L), got: $other")
        }
    }

    "MysqlBufferReader readLengthEncodedInt 0xFD prefix encodes 1" in {
        val reader = MysqlBufferReader(Array[Byte](0xfd.toByte, 1, 0, 0))
        Abort.run[SqlDecodeException](reader.readLenencInt()).map {
            case Result.Success(v) => assert(v == 1L)
            case other             => fail(s"Expected Success(1L), got: $other")
        }
    }

    "MysqlBufferReader readLengthEncodedInt 0xFE prefix encodes 1" in {
        val bytes  = Array[Byte](0xfe.toByte, 1, 0, 0, 0, 0, 0, 0, 0)
        val reader = MysqlBufferReader(bytes)
        Abort.run[SqlDecodeException](reader.readLenencInt()).map {
            case Result.Success(v) => assert(v == 1L)
            case other             => fail(s"Expected Success(1L), got: $other")
        }
    }

    "MysqlBufferReader readLenencInt 0xFF in an integer position names the byte and its offset" in {
        val reader = MysqlBufferReader(Array[Byte](0xff.toByte))
        Abort.run[SqlDecodeException](reader.readLenencInt()).map {
            case Result.Failure(e: SqlDecodeProtocolFormatException) =>
                assert(e.messageByte == 0xff.toByte)
                assert(e.position == 0)
            case other => fail(s"Expected a protocol-format failure naming 0xFF at offset 0, got: $other")
        }
    }

    // 0xFB is 251, so it fails the `v <= 250` arm and reaches the reserved arm exactly as 0xFF does. A reader that
    // mapped both to one `Absent` and discarded which would leave every caller free to hardcode 0xFF into its
    // message and name the wrong byte whenever it was this one. Asserting 0xFB here pins the byte to the input.
    "MysqlBufferReader readLenencInt 0xFB in an integer position names 0xFB, not 0xFF" in {
        val reader = MysqlBufferReader(Array[Byte](0xfb.toByte))
        Abort.run[SqlDecodeException](reader.readLenencInt()).map {
            case Result.Failure(e: SqlDecodeProtocolFormatException) =>
                assert(e.messageByte == 0xfb.toByte)
                assert(e.position == 0)
            case other => fail(s"Expected a protocol-format failure naming 0xFB at offset 0, got: $other")
        }
    }

    // The one genuine absence on this wire is a text-protocol column that is SQL NULL: it reads back as Maybe.Absent,
    // while a reserved marker becomes an abort. The two must not collapse to one Absent.
    "MysqlBufferReader readLenencBytes 0xFB is SQL NULL" in {
        val reader = MysqlBufferReader(Array[Byte](0xfb.toByte))
        Abort.run[SqlDecodeException](reader.readLenencBytes()).map {
            case Result.Success(Maybe.Absent) => succeed
            case other                        => fail(s"Expected Success(Absent) for the 0xFB NULL marker, got: $other")
        }
    }

    "MysqlBufferReader readLenencBytes reads a length-prefixed column's bytes" in {
        val reader = MysqlBufferReader(Array[Byte](3, 'a'.toByte, 'b'.toByte, 'c'.toByte))
        Abort.run[SqlDecodeException](reader.readLenencBytes()).map {
            case Result.Success(Maybe.Present(bytes)) =>
                assert(bytes.toArray.toSeq == Seq[Byte]('a', 'b', 'c'))
            case other => fail(s"Expected Success(Present([a,b,c])), got: $other")
        }
    }

    "MysqlBufferReader readLenencBytes 0xFF names the byte and its offset" in {
        val reader = MysqlBufferReader(Array[Byte](0xff.toByte))
        Abort.run[SqlDecodeException](reader.readLenencBytes()).map {
            case Result.Failure(e: SqlDecodeProtocolFormatException) =>
                assert(e.messageByte == 0xff.toByte)
                assert(e.position == 0)
            case other => fail(s"Expected a protocol-format failure naming 0xFF at offset 0, got: $other")
        }
    }

    // The offset is the offending byte's own, not the cursor past it, which is what makes it usable for locating
    // the desynchronisation in a captured packet.
    "MysqlBufferReader readLenencInt reports the reserved byte's own offset, not the cursor past it" in {
        val reader = MysqlBufferReader(Array[Byte](7, 0xff.toByte))
        Abort.run[SqlDecodeException](reader.readLenencInt().flatMap(_ => reader.readLenencInt())).map {
            case Result.Failure(e: SqlDecodeProtocolFormatException) =>
                assert(e.messageByte == 0xff.toByte)
                assert(e.position == 1)
            case other => fail(s"Expected a protocol-format failure naming 0xFF at offset 1, got: $other")
        }
    }

    "MysqlBufferReader readLengthEncodedString decodes 5-byte string" in {
        val bytes  = Array[Byte](5, 'h'.toByte, 'e'.toByte, 'l'.toByte, 'l'.toByte, 'o'.toByte)
        val reader = MysqlBufferReader(bytes)
        Abort.run[SqlDecodeException](reader.readLenencString()).map {
            case Result.Success(v) => assert(v == "hello")
            case other             => fail(s"Expected Success(\"hello\"), got: $other")
        }
    }

    "MysqlBufferReader readNulTerminatedString stops at NUL and excludes it" in {
        val bytes  = Array[Byte]('h'.toByte, 'i'.toByte, 0, 'x'.toByte)
        val reader = MysqlBufferReader(bytes)
        assert(reader.readNulTerminatedString() == "hi")
        assert(reader.remaining == 1) // 'x' still remaining
    }

    "MysqlBufferReader readFixedString reads exactly n bytes" in {
        val bytes  = Array[Byte]('A'.toByte, 'B'.toByte, 'C'.toByte, 'D'.toByte)
        val reader = MysqlBufferReader(bytes)
        assert(reader.readFixedString(3) == "ABC")
        assert(reader.remaining == 1) // 'D' still remaining
    }

    "MysqlBufferReader readUInt24LE [5,0,1] = 65541" in {
        val reader = MysqlBufferReader(Array[Byte](5, 0, 1))
        // 5 + (0 << 8) + (1 << 16) = 65541
        Abort.run[SqlDecodeException](reader.readUInt24LE()).map {
            case Result.Success(v) => assert(v == 65541)
            case other             => fail(s"Expected Success(65541), got: $other")
        }
    }

    "MysqlBufferReader readByte returns Abort.fail on empty buffer" in {
        val reader = MysqlBufferReader(Array.empty[Byte])
        Abort.run[SqlDecodeException](reader.readByte()).map {
            case Result.Failure(_: SqlDecodeException) => succeed
            case other                                 => fail(s"Expected Decode failure, got: $other")
        }
    }

    "MysqlBufferReader readUInt16LE returns Abort.fail on under-length buffer" in {
        val reader = MysqlBufferReader(Array[Byte](1)) // only 1 byte, need 2
        Abort.run[SqlDecodeException](reader.readUInt16LE()).map {
            case Result.Failure(_: SqlDecodeException) => succeed
            case other                                 => fail(s"Expected Decode failure, got: $other")
        }
    }

    "MysqlBufferReader readBytes returns Abort.fail on under-length buffer" in {
        val reader = MysqlBufferReader(Array[Byte](1, 2)) // only 2 bytes
        Abort.run[SqlDecodeException](reader.readBytes(5)).map {
            case Result.Failure(_: SqlDecodeException) => succeed
            case other                                 => fail(s"Expected Decode failure, got: $other")
        }
    }

end MysqlBufferReaderTest
