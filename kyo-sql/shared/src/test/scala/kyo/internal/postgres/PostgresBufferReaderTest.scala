package kyo.internal.postgres

import kyo.*
import kyo.Span
import kyo.SqlException
import kyo.Test

/** Tests for [[PostgresBufferReader]].
  *
  * Byte values are per the PostgreSQL v3 wire protocol big-endian encoding §55.7.
  */
class PostgresBufferReaderTest extends Test:

    "BufferReader readString stops at NUL" in {
        val bytes  = Array[Byte]('h', 'e', 'l', 'l', 'o', 0, 'x')
        val reader = PostgresBufferReader(bytes)
        val s      = reader.readString()
        assert(s == "hello")
        // Position should be past the NUL (position 6 = start of 'x')
        assert(reader.remaining == 1)
    }

    "BufferReader readInt32 big-endian" in {
        val bytes  = Array[Byte](0, 0, 1, 0)
        val reader = PostgresBufferReader(bytes)
        Abort.run[SqlDecodeException](reader.readInt32()).map {
            case Result.Success(v) => assert(v == 256)
            case other             => fail(s"Expected Success(256), got: $other")
        }
    }

    "BufferReader readInt16 big-endian" in {
        val bytes  = Array[Byte](1, 0)
        val reader = PostgresBufferReader(bytes)
        Abort.run[SqlDecodeException](reader.readInt16()).map {
            case Result.Success(v) => assert(v == 256.toShort)
            case other             => fail(s"Expected Success(256.toShort), got: $other")
        }
    }

    "BufferReader readByte returns Abort.fail on empty buffer" in {
        val reader = PostgresBufferReader(Array.empty[Byte])
        Abort.run[SqlDecodeException](reader.readByte()).map {
            case Result.Failure(_: SqlDecodeException) => succeed
            case other                                 => fail(s"Expected Decode failure, got: $other")
        }
    }

    "BufferReader readInt32 returns Abort.fail on under-length buffer" in {
        val reader = PostgresBufferReader(Array[Byte](1, 2)) // only 2 bytes, need 4
        Abort.run[SqlDecodeException](reader.readInt32()).map {
            case Result.Failure(_: SqlDecodeException) => succeed
            case other                                 => fail(s"Expected Decode failure, got: $other")
        }
    }

    "BufferReader readBytes returns Abort.fail on under-length buffer" in {
        val reader = PostgresBufferReader(Array[Byte](1, 2)) // only 2 bytes
        Abort.run[SqlDecodeException](reader.readBytes(4)).map {
            case Result.Failure(_: SqlDecodeException) => succeed
            case other                                 => fail(s"Expected Decode failure, got: $other")
        }
    }

end PostgresBufferReaderTest
